package com.reveil.reminder;

import com.reveil.device.DeviceService;
import com.reveil.device.DeviceToken;
import com.reveil.event.Event;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Envoi des rappels échus aux appareils du propriétaire de l'événement. */
@Service
public class ReminderService {

    /** Type de notification (champ {@code data.type}) reconnu par l'application mobile. */
    public static final String TYPE = "event-reminder";

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private final ReminderQueue queue;
    private final DeviceService devices;
    private final PushSender push;
    private final ReminderSettings settings;
    private final Clock clock;

    @Autowired
    public ReminderService(ReminderQueue queue, DeviceService devices, PushSender push, ReminderSettings settings) {
        this(queue, devices, push, settings, Clock.systemUTC());
    }

    ReminderService(ReminderQueue queue, DeviceService devices, PushSender push, ReminderSettings settings,
                    Clock clock) {
        this.queue = queue;
        this.devices = devices;
        this.push = push;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Traite tous les rappels échus ; renvoie le nombre d'événements notifiés. Chaque rappel est marqué envoyé
     * au moment où il est pris ({@link ReminderQueue#claimNextDue}) : il n'est jamais envoyé deux fois.
     */
    public int sendDueReminders() {
        Instant now = clock.instant();
        Instant dueBefore = now.plus(settings.lookahead());
        long skipped = queue.skipStarted(dueBefore, now);
        if (skipped > 0) {
            log.info("Rappels abandonnés pour {} événement(s) déjà commencé(s)", skipped);
        }
        int processed = 0;
        for (Optional<Event> next = queue.claimNextDue(dueBefore, now); next.isPresent();
             next = queue.claimNextDue(dueBefore, now)) {
            remind(next.get(), now);
            processed++;
        }
        return processed;
    }

    /** Convertit les événements antérieurs à la gestion des rappels multiples (rappel par défaut si à venir). */
    public long scheduleMissingReminders() {
        return queue.migrateLegacy(clock.instant(), settings.leadTime());
    }

    private void remind(Event event, Instant now) {
        if (!event.getStartsAt().isAfter(now)) {
            // Rappel en retard (API arrêtée) et événement déjà commencé : un rappel n'a plus de sens.
            log.info("Rappel abandonné : l'événement {} a déjà commencé", event.getId());
            return;
        }
        String title = "Rappel : " + event.getTitle();
        String body = body(event, now);
        Map<String, String> data = Map.of(
                "type", TYPE,
                "eventId", event.getId(),
                "link", "reveil://events/" + event.getId());
        for (DeviceToken device : devices.activeDevicesOf(event.getOwnerId())) {
            PushResult result = push.send(new PushMessage(device.getToken(), title, body, data, event.getStartsAt(),
                    "reminder-" + event.getId()));
            if (result == PushResult.INVALID_TOKEN) {
                devices.forget(device.getToken());
            } else if (result == PushResult.FAILED) {
                log.warn("Rappel de l'événement {} non remis à un appareil", event.getId());
            }
        }
    }

    /**
     * « Commence dans 1 h, à 20:30 · Olympia » ; à partir d'un jour : « Commence dans 7 jours, le 24/12 à 20:30 »
     * (heure dans le fuseau de saisie de l'événement).
     */
    static String body(Event event, Instant now) {
        long minutes = Math.round(Duration.between(now, event.getStartsAt()).toSeconds() / 60.0);
        String in;
        if (minutes < 1) {
            in = "maintenant";
        } else if (minutes < 60) {
            in = "dans " + minutes + " min";
        } else if (minutes < 1440) {
            in = "dans " + (minutes / 60) + " h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
        } else {
            long days = minutes / 1440;
            long hours = (minutes % 1440) / 60;
            in = "dans " + days + (days > 1 ? " jours" : " jour") + (hours == 0 ? "" : " " + hours + " h");
        }
        ZonedDateTime local = event.getStartsAt().atZone(ZoneId.of(event.getTimeZone()));
        String at = (minutes >= 1440 ? "le " + DAY.format(local) + " à " : "à ") + HOUR.format(local);
        String where = event.getLocation() == null ? "" : " · " + event.getLocation();
        return "Commence " + in + ", " + at + where;
    }
}
