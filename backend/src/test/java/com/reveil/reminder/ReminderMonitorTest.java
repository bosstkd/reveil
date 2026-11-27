package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.reveil.event.Event;
import com.reveil.event.Reminder;
import com.reveil.event.ReminderStatus;
import com.reveil.mail.EmailMessage;
import com.reveil.mail.EmailSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ReminderMonitorTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    SimpleMeterRegistry registry;
    EmailSender email;
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        email = mock(EmailSender.class);
        logger = (Logger) LoggerFactory.getLogger(ReminderMonitor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private ReminderMonitor monitor(String alertEmail) {
        return new ReminderMonitor(registry, new ReminderMonitoringSettings(Duration.ofHours(1), Duration.ofMinutes(1),
                0.05, 20, alertEmail), email, Runnable::run, clock);
    }

    private static Event event(String id) {
        Event e = new Event("Concert", Instant.parse("2026-11-27T12:00:00Z"), "UTC", null, "u1");
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static void deliver(ReminderMonitor m, int success, int failure) {
        for (int i = 0; i < success; i++) {
            m.delivery(DeliveryChannel.FCM, DeliveryStatus.SUCCESS, event("e"));
        }
        for (int i = 0; i < failure; i++) {
            m.delivery(DeliveryChannel.WEB_PUSH, DeliveryStatus.FAILURE, event("e"));
        }
    }

    private static Map<String, Object> keyValues(ILoggingEvent e) {
        return e.getKeyValuePairs() == null ? Map.of()
                : e.getKeyValuePairs().stream().collect(java.util.HashMap::new, (m, kv) -> m.put(kv.key, kv.value), Map::putAll);
    }

    @Test
    void eachDeliveryIsLoggedWithStructuredStatus() {
        ReminderMonitor m = monitor("");
        m.delivery(DeliveryChannel.FCM, DeliveryStatus.SUCCESS, event("e1"));
        m.delivery(DeliveryChannel.WEB_PUSH, DeliveryStatus.FAILURE, event("e2"));
        m.delivery(DeliveryChannel.FCM, DeliveryStatus.INVALID_TOKEN, event("e3"));

        assertThat(logs.list).hasSize(3);
        assertThat(keyValues(logs.list.get(0))).containsEntry("reminder.channel", "fcm")
                .containsEntry("reminder.status", "success").containsEntry("event.id", "e1");
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValues(logs.list.get(1))).containsEntry("reminder.channel", "webpush")
                .containsEntry("reminder.status", "failure");
        assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(1).getFormattedMessage()).contains("statut=failure (échec)");
        assertThat(keyValues(logs.list.get(2))).containsEntry("reminder.status", "invalid_token");
        assertThat(logs.list.get(2).getFormattedMessage()).contains("jeton invalide");
        assertThat(registry.find(ReminderMonitor.DELIVERIES).tags("channel", "fcm", "status", "invalid_token")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void onTimeRatioIsThePartOfRemindersSentWithinOneMinute() {
        ReminderMonitor m = monitor("");
        Instant due = now.get();
        m.dispatched(event("e1"), List.of(new Reminder(60, due, ReminderStatus.SENT, due)), due.plusSeconds(59), 1);
        m.dispatched(event("e2"), List.of(new Reminder(60, due, ReminderStatus.SENT, due)), due.minusSeconds(5), 1);
        m.dispatched(event("e3"), List.of(new Reminder(60, due, ReminderStatus.SENT, due)), due.plusSeconds(60), 1);
        m.dispatched(event("e4"), List.of(new Reminder(60, due, ReminderStatus.SENT, due)), due.plusSeconds(600), 0);

        assertThat(m.snapshot().reminders().sent()).isEqualTo(4);
        assertThat(m.snapshot().reminders().onTime()).isEqualTo(2);
        assertThat(m.snapshot().onTimeRate()).isEqualTo(0.5);
        assertThat(registry.find(ReminderMonitor.ON_TIME_RATIO).gauge().value()).isEqualTo(0.5);
        assertThat(registry.find(ReminderMonitor.DISPATCHED).tags("on_time", "false").counter().count()).isEqualTo(2);
        assertThat(keyValues(logs.list.get(3))).containsEntry("reminder.on_time", false)
                .containsEntry("reminder.delay_ms", 600_000L).containsEntry("reminder.recipients", 0);
    }

    @Test
    void noDataGivesNoRatio() {
        ReminderMonitor m = monitor("");
        assertThat(m.snapshot().onTimeRate()).isNull();
        assertThat(m.snapshot().failureRate()).isNull();
        assertThat(registry.find(ReminderMonitor.FAILURE_RATIO).gauge().value()).isNaN();
        assertThat(m.evaluate()).isFalse();
    }

    @Test
    void alertFiresAboveFivePercentOfFailuresInTheLastHourThenResolves() {
        ReminderMonitor m = monitor("ops@example.com");
        deliver(m, 95, 5); // 5 % : seuil non dépassé
        assertThat(m.evaluate()).isFalse();

        deliver(m, 0, 1); // 6/101 > 5 %
        assertThat(m.evaluate()).isTrue();
        assertThat(m.evaluate()).isTrue(); // pas de seconde notification
        assertThat(registry.find(ReminderMonitor.ALERTS).counter().count()).isEqualTo(1);
        assertThat(registry.find(ReminderMonitor.ALERT_ACTIVE).gauge().value()).isEqualTo(1);
        ILoggingEvent alert = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(keyValues(alert)).containsEntry("alert", "reminder_failure_rate").containsEntry("alert.state", "firing");
        assertThat(alert.getFormattedMessage()).contains("ALERTE").contains("5,9 %");
        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("ops@example.com");
        assertThat(mail.getValue().subject()).contains("taux d'échec");

        now.set(now.get().plus(Duration.ofMinutes(61))); // les échecs sortent de la fenêtre d'1 h
        assertThat(m.evaluate()).isFalse();
        assertThat(m.snapshot().alert().active()).isFalse();
        assertThat(logs.list).anySatisfy(e -> assertThat(keyValues(e)).containsEntry("alert.state", "resolved"));
    }

    @Test
    void invalidTokensAreNotFailures() {
        ReminderMonitor m = monitor("");
        for (int i = 0; i < 30; i++) {
            m.delivery(DeliveryChannel.FCM, DeliveryStatus.INVALID_TOKEN, event("e"));
        }
        assertThat(m.snapshot().failureRate()).isZero();
        assertThat(m.evaluate()).isFalse();
    }

    @Test
    void tooFewDeliveriesDoNotTriggerTheAlert() {
        ReminderMonitor m = monitor("ops@example.com");
        deliver(m, 10, 9);
        assertThat(m.evaluate()).isFalse();
        deliver(m, 0, 1); // 20 envois : évaluée
        assertThat(m.evaluate()).isTrue();
    }

    @Test
    void withoutRecipientTheAlertIsOnlyLoggedAndAMailFailureIsIgnored() {
        ReminderMonitor m = monitor("");
        deliver(m, 0, 20);
        assertThat(m.evaluate()).isTrue();
        verify(email, never()).send(any());

        ReminderMonitor other = new ReminderMonitor(new SimpleMeterRegistry(), new ReminderMonitoringSettings(
                Duration.ofHours(1), Duration.ofMinutes(1), 0.05, 20, "ops@example.com"), email,
                r -> { throw new IllegalStateException("file pleine"); }, clock);
        deliver(other, 0, 20);
        assertThat(other.evaluate()).isTrue();
    }
}
