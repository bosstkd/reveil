package com.reveil.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.session.SessionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeviceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    DeviceTokenRepository repository;
    SessionService sessions;
    DeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeviceTokenRepository.class);
        sessions = mock(SessionService.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DeviceService(repository, sessions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DeviceToken saved() {
        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void registersANewTokenForTheUserAndSession() {
        when(repository.findByToken("tok")).thenReturn(Optional.empty());
        service.register("u1", "s1", new RegisterDeviceRequest(" tok ", DevicePlatform.ANDROID));

        DeviceToken d = saved();
        assertThat(d.getToken()).isEqualTo("tok");
        assertThat(d.getUserId()).isEqualTo("u1");
        assertThat(d.getSessionId()).isEqualTo("s1");
        assertThat(d.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(d.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void knownTokenIsReassignedToTheNewUser() {
        DeviceToken existing = new DeviceToken("tok", "u1", "s1", DevicePlatform.IOS, Instant.EPOCH);
        when(repository.findByToken("tok")).thenReturn(Optional.of(existing));
        service.register("u2", "s9", new RegisterDeviceRequest("tok", DevicePlatform.IOS));

        DeviceToken d = saved();
        assertThat(d).isSameAs(existing);
        assertThat(d.getUserId()).isEqualTo("u2");
        assertThat(d.getSessionId()).isEqualTo("s9");
        assertThat(d.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(d.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void devicesOfClosedSessionsAreExcludedAndDeleted() {
        DeviceToken active = new DeviceToken("a", "u1", "s1", DevicePlatform.ANDROID, NOW);
        DeviceToken loggedOut = new DeviceToken("b", "u1", "s2", DevicePlatform.IOS, NOW);
        when(repository.findByUserId("u1")).thenReturn(List.of(active, loggedOut));
        when(sessions.isActive("s1")).thenReturn(true);
        when(sessions.isActive("s2")).thenReturn(false);

        assertThat(service.activeDevicesOf("u1")).containsExactly(active);
        verify(repository).delete(loggedOut);
        verify(repository, never()).delete(active);
    }

    @Test
    void forgetDeletesTheToken() {
        service.forget("tok");
        verify(repository).deleteByToken("tok");
    }
}
