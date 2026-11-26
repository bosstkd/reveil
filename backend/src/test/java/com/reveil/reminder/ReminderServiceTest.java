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
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
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
    ReminderService service;

    @BeforeEach
    void setUp() {
        queue = mock(ReminderQueue.class);
        devices = mock(DeviceService.class);
        push = mock(PushSender.class);
        service = new ReminderService(queue, devices, push, new ReminderSettings(Duration.ofHours(1), Duration.ofSeconds(5)),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
    void scheduleMissingUsesTheConfiguredLeadTime() {
        when(queue.scheduleMissing(NOW, Duration.ofHours(1))).thenReturn(3L);
        assertThat(service.scheduleMissingReminders()).isEqualTo(3L);
        verify(queue).scheduleMissing(eq(NOW), eq(Duration.ofHours(1)));
    }
}
