package com.reveil.reminder;

import com.reveil.event.Event;
import com.reveil.event.Reminder;
import com.reveil.mail.EmailMessage;
import com.reveil.mail.EmailSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Supervision de l'envoi des rappels (story 15) :
 * <ul>
 *   <li>journal structuré de chaque envoi (paires clé/valeur SLF4J : {@code reminder.channel},
 *       {@code reminder.status}, {@code event.id}… ; JSON avec {@code logging.structured.format.console}) ;</li>
 *   <li>métriques Micrometer (exposées à Prometheus) : envois par canal/statut, rappels à l'heure ou en retard,
 *       écart à l'heure prévue, taux sur la fenêtre glissante ;</li>
 *   <li>alerte quand le taux d'échec des envois dépasse {@code failureThreshold} sur la fenêtre (1 h) :
 *       journal ERROR, compteur {@code reveil.reminders.alerts}, e-mail facultatif ; levée journalisée.</li>
 * </ul>
 */
@Component
public class ReminderMonitor {

    static final String DELIVERIES = "reveil.reminders.deliveries";
    static final String DISPATCHED = "reveil.reminders.dispatched";
    static final String DELAY = "reveil.reminders.dispatch.delay";
    static final String SKIPPED = "reveil.reminders.skipped";
    static final String ALERTS = "reveil.reminders.alerts";
    static final String ON_TIME_RATIO = "reveil.reminders.on.time.ratio";
    static final String FAILURE_RATIO = "reveil.reminders.failure.ratio";
    static final String ALERT_ACTIVE = "reveil.reminders.alert.active";
    static final String ALERT_NAME = "reminder_failure_rate";

    private static final Logger log = LoggerFactory.getLogger(ReminderMonitor.class);

    // Index des compteurs de la fenêtre glissante.
    private static final int SUCCESS = 0;
    private static final int FAILURE = 1;
    private static final int INVALID = 2;
    private static final int ON_TIME = 3;
    private static final int LATE = 4;

    private final ReminderMonitoringSettings settings;
    private final EmailSender email;
    private final Executor alertExecutor;
    private final SlidingWindow window;
    private final Map<DeliveryChannel, Map<DeliveryStatus, Counter>> deliveries = new EnumMap<>(DeliveryChannel.class);
    private final Counter onTime;
    private final Counter late;
    private final Counter skipped;
    private final Counter alerts;
    private final Timer delay;
    private boolean alertActive;

    @Autowired
    public ReminderMonitor(MeterRegistry registry, ReminderMonitoringSettings settings, EmailSender email) {
        this(registry, settings, email, newAlertExecutor(), Clock.systemUTC());
    }

    ReminderMonitor(MeterRegistry registry, ReminderMonitoringSettings settings, EmailSender email,
                    Executor alertExecutor, Clock clock) {
        this.settings = settings;
        this.email = email;
        this.alertExecutor = alertExecutor;
        this.window = new SlidingWindow(clock, settings.window(), 60, 5);
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            Map<DeliveryStatus, Counter> byStatus = new EnumMap<>(DeliveryStatus.class);
            for (DeliveryStatus status : DeliveryStatus.values()) {
                byStatus.put(status, Counter.builder(DELIVERIES)
                        .description("Envois de rappels par canal et statut (success, failure, invalid_token)")
                        .tag("channel", channel.code()).tag("status", status.code())
                        .register(registry));
            }
            deliveries.put(channel, byStatus);
        }
        onTime = dispatched(registry, true);
        late = dispatched(registry, false);
        skipped = Counter.builder(SKIPPED)
                .description("Événements dont les rappels ont été abandonnés (déjà commencés à l'échéance)")
                .register(registry);
        alerts = Counter.builder(ALERTS).description("Alertes déclenchées sur le taux d'échec des envois")
                .tag("alert", ALERT_NAME).register(registry);
        delay = Timer.builder(DELAY)
                .description("Écart entre l'heure prévue d'un rappel et son envoi (avance comptée 0)")
                .serviceLevelObjectives(settings.onTimeTolerance())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofHours(1))
                .register(registry);
        Gauge.builder(ON_TIME_RATIO, this, m -> nanIfNull(m.snapshot().onTimeRate()))
                .description("Part des rappels envoyés à moins d'1 min de l'heure prévue (fenêtre glissante)")
                .register(registry);
        Gauge.builder(FAILURE_RATIO, this, m -> nanIfNull(m.snapshot().failureRate()))
                .description("Taux d'échec des envois de rappels (fenêtre glissante)")
                .register(registry);
        Gauge.builder(ALERT_ACTIVE, this, m -> m.isAlertActive() ? 1 : 0)
                .description("1 si l'alerte sur le taux d'échec est active")
                .register(registry);
    }

    private static Counter dispatched(MeterRegistry registry, boolean onTime) {
        return Counter.builder(DISPATCHED)
                .description("Rappels envoyés, à l'heure (écart < 1 min) ou non")
                .tag("on_time", String.valueOf(onTime))
                .register(registry);
    }

    /** Un envoi à un destinataire (appareil ou navigateur) : journalisé et compté. */
    public void delivery(DeliveryChannel channel, DeliveryStatus status, Event event) {
        deliveries.get(channel).get(status).increment();
        window.add(switch (status) {
            case SUCCESS -> SUCCESS;
            case FAILURE -> FAILURE;
            case INVALID_TOKEN -> INVALID;
        }, 1);
        var line = status == DeliveryStatus.FAILURE ? log.atWarn() : log.atInfo();
        line.addKeyValue("reminder.channel", channel.code())
                .addKeyValue("reminder.status", status.code())
                .addKeyValue("event.id", event.getId())
                .log("Envoi de rappel : canal={} statut={} ({}) événement={}", channel.code(), status.code(),
                        status.label(), event.getId());
    }

    /**
     * Rappels d'un événement traités par ce passage ({@code at} : fin des envois) : écart à l'heure prévue,
     * « à l'heure » si strictement inférieur à {@code onTimeTolerance} (un envoi anticipé par le planificateur compte).
     */
    public void dispatched(Event event, List<Reminder> reminders, Instant at, int recipients) {
        for (Reminder reminder : reminders) {
            Duration gap = Duration.between(reminder.getDueAt(), at);
            boolean punctual = gap.abs().compareTo(settings.onTimeTolerance()) < 0;
            (punctual ? onTime : late).increment();
            delay.record(gap.isNegative() ? Duration.ZERO : gap);
            window.add(punctual ? ON_TIME : LATE, 1);
            var line = punctual ? log.atInfo() : log.atWarn();
            line.addKeyValue("event.id", event.getId())
                    .addKeyValue("reminder.minutes_before", reminder.getMinutesBefore())
                    .addKeyValue("reminder.due_at", reminder.getDueAt().toString())
                    .addKeyValue("reminder.delay_ms", gap.toMillis())
                    .addKeyValue("reminder.on_time", punctual)
                    .addKeyValue("reminder.recipients", recipients)
                    .log("Rappel envoyé : événement={} délai={} min écart={} ms à_l_heure={} destinataires={}",
                            event.getId(), reminder.getMinutesBefore(), gap.toMillis(), punctual, recipients);
        }
    }

    /** Rappels abandonnés pour {@code events} événement(s) déjà commencé(s). */
    public void skipped(long events) {
        if (events > 0) {
            skipped.increment(events);
        }
    }

    /**
     * Évalue l'alerte (à chaque passage du planificateur) : déclenchée quand le taux d'échec dépasse le seuil sur
     * la fenêtre (au moins {@code minDeliveries} envois), levée quand il repasse sous le seuil. Une seule
     * notification par épisode. Renvoie l'état après évaluation.
     */
    public synchronized boolean evaluate() {
        ReminderMonitoringSnapshot s = snapshot();
        long total = s.deliveries().total();
        boolean breached = s.failureRate() != null && total >= settings.minDeliveries()
                && s.failureRate() > settings.failureThreshold();
        if (breached && !alertActive) {
            alertActive = true;
            alerts.increment();
            log.atError()
                    .addKeyValue("alert", ALERT_NAME)
                    .addKeyValue("alert.state", "firing")
                    .addKeyValue("reminder.failure_rate", s.failureRate())
                    .addKeyValue("reminder.failures", s.deliveries().failure())
                    .addKeyValue("reminder.deliveries", total)
                    .log("ALERTE rappels : taux d'échec {} sur {} ({} échec(s) / {} envoi(s)), seuil {}",
                            percent(s.failureRate()), settings.window(), s.deliveries().failure(), total,
                            percent(settings.failureThreshold()));
            sendAlertEmail(s);
        } else if (!breached && alertActive) {
            alertActive = false;
            log.atInfo()
                    .addKeyValue("alert", ALERT_NAME)
                    .addKeyValue("alert.state", "resolved")
                    .addKeyValue("reminder.failure_rate", s.failureRate())
                    .log("Alerte rappels levée : taux d'échec {} sur {}",
                            s.failureRate() == null ? "n/a" : percent(s.failureRate()), settings.window());
        }
        return alertActive;
    }

    public synchronized boolean isAlertActive() {
        return alertActive;
    }

    /** Indicateurs sur la fenêtre glissante. */
    public ReminderMonitoringSnapshot snapshot() {
        long[] t = window.totals();
        long total = t[SUCCESS] + t[FAILURE] + t[INVALID];
        long sent = t[ON_TIME] + t[LATE];
        return new ReminderMonitoringSnapshot(settings.window(),
                new ReminderMonitoringSnapshot.Deliveries(total, t[SUCCESS], t[FAILURE], t[INVALID]),
                total == 0 ? null : (double) t[FAILURE] / total,
                new ReminderMonitoringSnapshot.Reminders(sent, t[ON_TIME], t[LATE], settings.onTimeTolerance()),
                sent == 0 ? null : (double) t[ON_TIME] / sent,
                new ReminderMonitoringSnapshot.Alert(isAlertActive(), settings.failureThreshold(),
                        settings.minDeliveries()));
    }

    private void sendAlertEmail(ReminderMonitoringSnapshot s) {
        String to = settings.alertEmail() == null ? "" : settings.alertEmail().trim();
        if (to.isEmpty()) {
            return;
        }
        EmailMessage message = new EmailMessage(to, "[Reveil] Alerte : taux d'échec des rappels " + percent(s.failureRate()),
                "Le taux d'échec des envois de rappels a dépassé " + percent(settings.failureThreshold())
                        + " sur " + settings.window() + ".\n\n"
                        + "Envois : " + s.deliveries().total() + " (succès " + s.deliveries().success()
                        + ", échecs " + s.deliveries().failure() + ", jetons invalides "
                        + s.deliveries().invalidToken() + ").\n"
                        + "Rappels à l'heure : " + (s.onTimeRate() == null ? "n/a" : percent(s.onTimeRate())) + ".\n");
        try {
            alertExecutor.execute(() -> {
                try {
                    email.send(message);
                } catch (RuntimeException e) {
                    log.warn("E-mail d'alerte non envoyé : {}", e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.warn("E-mail d'alerte non envoyé : {}", e.toString());
        }
    }

    private static String percent(double ratio) {
        return String.format(Locale.FRANCE, "%.1f %%", ratio * 100);
    }

    private static double nanIfNull(Double value) {
        return value == null ? Double.NaN : value;
    }

    /** L'e-mail d'alerte (SMTP, jusqu'à 10 s) ne retarde pas les rappels : un fil dédié, file bornée. */
    private static Executor newAlertExecutor() {
        return new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10), r -> {
            Thread t = new Thread(r, "reveil-alert-mail");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }
}
