package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.device.DevicePlatform;
import com.reveil.device.DeviceService;
import com.reveil.device.DeviceToken;
import com.reveil.event.Event;
import com.reveil.event.Reminder;
import com.reveil.event.ReminderStatus;
import com.reveil.mail.EmailSender;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import com.reveil.webpush.WebPushMessage;
import com.reveil.webpush.WebPushService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T18:30:00Z");

    ReminderQueue queue;
    DeviceService devices;
    PushSender push;
    WebPushService webPush;
    ReminderService service;
    SimpleMeterRegistry registry;
    ReminderMonitor monitor;

    @BeforeEach
    void setUp() {
        queue = mock(ReminderQueue.class);
        devices = mock(DeviceService.class);
        push = mock(PushSender.class);
        webPush = mock(WebPushService.class);
        registry = new SimpleMeterRegistry();
        monitor = new ReminderMonitor(registry, new ReminderMonitoringSettings(Duration.ofHours(1), Duration.ofMinutes(1),
                0.05, 20, ""), mock(EmailSender.class), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ReminderService(queue, devices, push, webPush, new ReminderSettings(Duration.ofHours(1), Duration.ofSeconds(5)),
                monitor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Event event(String id, Instant startsAt, String location) {
        Event e = new Event("Concert", startsAt, "Europe/Paris", location, "u1");
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static DeviceToken device(String token) {
        return new DeviceToken(token, "u1", "s1", DevicePlatform.ANDROID, NOW);
    }

    @Test
    void sendsEachDueReminderToEveryActiveDeviceOfTheOwner() {
        when(queue.claimNextDue(NOW.plusSeconds(5), NOW))
                .thenReturn(Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), "Olympia")), Optional.empty());
        when(devices.activeDevicesOf("u1")).thenReturn(List.of(device("a"), device("b")));
        when(push.send(any())).thenReturn(PushResult.SENT);

        assertThat(service.sendDueReminders()).isEqualTo(1);

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(PushMessage::token).containsExactly("a", "b");
        PushMessage m = sent.getValue();
        assertThat(m.title()).isEqualTo("Rappel : Concert");
        assertThat(m.body()).isEqualTo("Commence dans 1 h, à 20:30 · Olympia"); // heure de Paris
        assertThat(m.data()).containsEntry("eventId", "e1").containsEntry("link", "reveil://events/e1")
                .containsEntry("type", ReminderService.TYPE);
        assertThat(m.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        verify(devices, never()).forget(any());
    }

    @Test
    void invalidTokensAreForgottenAndFailuresKeepTheToken() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), null)), Optional.empty());
        when(devices.activeDevicesOf("u1")).thenReturn(List.of(device("gone"), device("flaky"), device("ok")));
        when(push.send(any())).thenAnswer(inv -> switch (((PushMessage) inv.getArgument(0)).token()) {
            case "gone" -> PushResult.INVALID_TOKEN;
            case "flaky" -> PushResult.FAILED;
            default -> PushResult.SENT;
        });

        service.sendDueReminders();

        verify(devices).forget("gone");
        verify(devices, never()).forget("flaky");
        verify(devices, never()).forget("ok");
    }

    @Test
    void reminderOfAnAlreadyStartedEventIsDroppedWithoutSending() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.minusSeconds(60), null)), Optional.empty());

        assertThat(service.sendDueReminders()).isEqualTo(1);
        verify(push, never()).send(any());
    }

    @Test
    void nothingDueSendsNothing() {
        when(queue.claimNextDue(any(), any())).thenReturn(Optional.empty());
        assertThat(service.sendDueReminders()).isZero();
        verify(push, never()).send(any());
    }

    @Test
    void bodyAdaptsToTheRemainingTime() {
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofMinutes(25)), null), NOW))
                .isEqualTo("Commence dans 25 min, à 19:55");
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofMinutes(90)), null), NOW))
                .isEqualTo("Commence dans 1 h 30 min, à 21:00");
        // Anticipation de quelques secondes : toujours « 1 h ».
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofHours(1)), null), NOW.minusSeconds(5)))
                .isEqualTo("Commence dans 1 h, à 20:30");
        assertThat(ReminderService.body(event("e", NOW.plusSeconds(10), null), NOW))
                .isEqualTo("Commence maintenant, à 19:30");
    }

    @Test
    void scheduleMissingMigratesWithTheConfiguredLeadTime() {
        when(queue.migrateLegacy(NOW, Duration.ofHours(1))).thenReturn(3L);
        assertThat(service.scheduleMissingReminders()).isEqualTo(3L);
        verify(queue).migrateLegacy(eq(NOW), eq(Duration.ofHours(1)));
    }

    @Test
    void remindersOfAlreadyStartedEventsAreSkippedBeforeSending() {
        when(queue.claimNextDue(any(), any())).thenReturn(Optional.empty());
        service.sendDueReminders();
        verify(queue).skipStarted(NOW.plusSeconds(5), NOW);
    }

    @Test
    void bodyCountsDaysForEarlyReminders() {
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofDays(7)), "Olympia"), NOW))
                .isEqualTo("Commence dans 7 jours, le 02/12 à 19:30 · Olympia");
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofDays(1)), null), NOW))
                .isEqualTo("Commence dans 1 jour, le 26/11 à 19:30");
        assertThat(ReminderService.body(event("e", NOW.plus(Duration.ofHours(26)), null), NOW))
                .isEqualTo("Commence dans 1 jour 2 h, le 26/11 à 21:30");
    }

    @Test
    void theReminderIsAlsoSentToTheOwnerBrowsers() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), "Olympia")), Optional.empty());
        when(devices.activeDevicesOf("u1")).thenReturn(List.of());

        assertThat(service.sendDueReminders()).isEqualTo(1);

        ArgumentCaptor<WebPushMessage> sent = ArgumentCaptor.forClass(WebPushMessage.class);
        verify(webPush).deliver(eq("u1"), sent.capture());
        WebPushMessage m = sent.getValue();
        assertThat(m.title()).isEqualTo("Rappel : Concert");
        assertThat(m.body()).isEqualTo("Commence dans 1 h, à 20:30 · Olympia");
        assertThat(m.url()).isEqualTo("evenements/e1");
        assertThat(m.tag()).isEqualTo("reminder-e1");
        assertThat(m.topic()).isEqualTo("e1");
        assertThat(m.data()).containsEntry("eventId", "e1").containsEntry("type", ReminderService.TYPE);
        assertThat(m.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void aBrowserFailureDoesNotStopTheOtherReminders() {
        when(queue.claimNextDue(any(), any())).thenReturn(
                Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), null)),
                Optional.of(event("e2", NOW.plus(Duration.ofHours(1)), null)), Optional.empty());
        when(webPush.deliver(any(), any())).thenThrow(new IllegalStateException("panne")).thenReturn(List.of(PushResult.SENT));

        assertThat(service.sendDueReminders()).isEqualTo(2);
        verify(webPush, times(2)).deliver(eq("u1"), any());
    }

    @Test
    void nothingIsSentToBrowsersForAnAlreadyStartedEvent() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.minusSeconds(60), null)), Optional.empty());
        service.sendDueReminders();
        verify(webPush, never()).deliver(any(), any());
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static Event withReminders(Event e, Reminder... reminders) {
        ReflectionTestUtils.setField(e, "reminders", List.of(reminders));
        return e;
    }

    @Test
    void everyDeliveryIsCountedWithItsChannelAndStatus() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), null)), Optional.empty());
        when(devices.activeDevicesOf("u1")).thenReturn(List.of(device("gone"), device("flaky"), device("ok")));
        when(push.send(any())).thenAnswer(inv -> switch (((PushMessage) inv.getArgument(0)).token()) {
            case "gone" -> PushResult.INVALID_TOKEN;
            case "flaky" -> PushResult.FAILED;
            default -> PushResult.SENT;
        });
        when(webPush.deliver(any(), any())).thenReturn(List.of(PushResult.SENT, PushResult.INVALID_TOKEN));

        service.sendDueReminders();

        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "fcm", "status", "success")).isEqualTo(1);
        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "fcm", "status", "failure")).isEqualTo(1);
        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "fcm", "status", "invalid_token")).isEqualTo(1);
        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "webpush", "status", "success")).isEqualTo(1);
        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "webpush", "status", "invalid_token")).isEqualTo(1);
        assertThat(monitor.snapshot().deliveries().total()).isEqualTo(5);
        assertThat(monitor.snapshot().failureRate()).isEqualTo(0.2);
    }

    @Test
    void aBrowserErrorCountsAsAFailedDelivery() {
        when(queue.claimNextDue(any(), any()))
                .thenReturn(Optional.of(event("e1", NOW.plus(Duration.ofHours(1)), null)), Optional.empty());
        when(webPush.deliver(any(), any())).thenThrow(new IllegalStateException("panne"));

        service.sendDueReminders();

        assertThat(count(ReminderMonitor.DELIVERIES, "channel", "webpush", "status", "failure")).isEqualTo(1);
    }

    @Test
    void theRemindersClaimedByThisPassAreMeasuredAgainstTheirDueTime() {
        Instant starts = NOW.plus(Duration.ofHours(1));
        Event e = withReminders(event("e1", starts, null),
                new Reminder(1440, starts.minus(Duration.ofDays(1)), ReminderStatus.SENT, NOW.minus(Duration.ofHours(23))),
                new Reminder(60, NOW.plusSeconds(3), ReminderStatus.SENT, NOW), // anticipé de 3 s : à l'heure
                new Reminder(15, starts.minus(Duration.ofMinutes(15)), ReminderStatus.SCHEDULED, null));
        when(queue.claimNextDue(any(), any())).thenReturn(Optional.of(e), Optional.empty());

        service.sendDueReminders();

        assertThat(count(ReminderMonitor.DISPATCHED, "on_time", "true")).isEqualTo(1);
        assertThat(count(ReminderMonitor.DISPATCHED, "on_time", "false")).isZero();
        assertThat(monitor.snapshot().onTimeRate()).isEqualTo(1.0);
    }

    @Test
    void aReminderSentMoreThanAMinuteLateIsNotOnTime() {
        Instant starts = NOW.plus(Duration.ofMinutes(50));
        Event e = withReminders(event("e1", starts, null),
                new Reminder(60, NOW.minus(Duration.ofMinutes(10)), ReminderStatus.SENT, NOW)); // API arrêtée 10 min
        when(queue.claimNextDue(any(), any())).thenReturn(Optional.of(e), Optional.empty());

        service.sendDueReminders();

        assertThat(count(ReminderMonitor.DISPATCHED, "on_time", "false")).isEqualTo(1);
        assertThat(registry.find(ReminderMonitor.DELAY).timer().totalTime(java.util.concurrent.TimeUnit.MINUTES))
                .isEqualTo(10.0);
        assertThat(monitor.snapshot().onTimeRate()).isEqualTo(0.0);
    }

    @Test
    void skippedRemindersAreCounted() {
        when(queue.skipStarted(any(), any())).thenReturn(2L);
        when(queue.claimNextDue(any(), any())).thenReturn(Optional.empty());

        service.sendDueReminders();

        assertThat(count(ReminderMonitor.SKIPPED)).isEqualTo(2);
    }

    @Test
    void theAlertIsEvaluatedAtEveryPassEvenWhenTheQueueFails() {
        when(queue.claimNextDue(any(), any())).thenThrow(new IllegalStateException("base indisponible"));
        for (int i = 0; i < 20; i++) {
            monitor.delivery(DeliveryChannel.FCM, DeliveryStatus.FAILURE, event("e" + i, NOW, null));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::sendDueReminders);
        assertThat(monitor.isAlertActive()).isTrue();
    }
}
