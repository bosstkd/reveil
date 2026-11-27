package com.reveil.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    final Map<String, Session> db = new HashMap<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    SessionService service;

    @BeforeEach
    void setUp() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            Session s = inv.getArgument(0);
            db.put(s.getId(), s);
            return s;
        });
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<String>getArgument(0))));
        doAnswer(inv -> db.remove(inv.<String>getArgument(0))).when(repo).deleteById(anyString());
        service = new SessionService(repo, Duration.ofDays(30), Duration.ofMinutes(1), clock);
    }

    @Test
    void storesOnlyAHashOfTheRefreshToken() {
        SessionService.Issued issued = service.open("u1");
        Session stored = db.get(issued.sessionId());
        String secret = issued.refreshToken().substring(issued.refreshToken().indexOf('.') + 1);
        assertThat(stored.getTokenHash()).isNotEqualTo(secret).isEqualTo(SessionService.hash(secret));
        assertThat(stored.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(service.isActive(issued.sessionId())).isTrue();
    }

    @Test
    void sessionStaysActiveFor30DaysWithoutUseThenExpires() {
        SessionService.Issued issued = service.open("u1");

        clock.advance(Duration.ofDays(30).minusMinutes(1));
        assertThat(service.isActive(issued.sessionId())).isTrue();
        SessionService.Issued renewed = service.refresh(issued.refreshToken());

        // Expiration glissante : 30 jours à compter du dernier rafraîchissement.
        clock.advance(Duration.ofDays(29));
        assertThat(service.refresh(renewed.refreshToken()).sessionId()).isEqualTo(issued.sessionId());

        SessionService.Issued other = service.open("u1");
        clock.advance(Duration.ofDays(30).plusSeconds(1));
        assertThat(service.isActive(other.sessionId())).isFalse();
        assertThatThrownBy(() -> service.refresh(other.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshRotatesTokenAndReplayAfterGraceRevokesTheSession() {
        SessionService.Issued first = service.open("u1");
        SessionService.Issued second = service.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.sessionId()).isEqualTo(first.sessionId());

        // Requête concurrente avec l'ancien jeton : tolérée pendant la fenêtre de grâce.
        clock.advance(Duration.ofSeconds(10));
        SessionService.Issued concurrent = service.refresh(first.refreshToken());
        assertThat(concurrent.sessionId()).isEqualTo(first.sessionId());

        // Rejeu tardif d'un ancien jeton : vol présumé, la session est révoquée.
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.refresh(first.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(service.isActive(first.sessionId())).isFalse();
        assertThatThrownBy(() -> service.refresh(concurrent.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rejectsMalformedOrUnknownTokens() {
        for (String t : new String[] {null, "", "sans-point", ".x", "x.", "inconnu.secret"}) {
            assertThatThrownBy(() -> service.refresh(t)).isInstanceOf(InvalidRefreshTokenException.class);
        }
    }

    @Test
    void revokeEndsOnlyTheDesignatedSession() {
        SessionService.Issued web = service.open("u1");
        SessionService.Issued mobile = service.open("u1");

        service.revoke(web.sessionId() + ".mauvais-secret");
        assertThat(service.isActive(web.sessionId())).isTrue();

        service.revoke(web.refreshToken());
        assertThat(service.isActive(web.sessionId())).isFalse();
        assertThat(service.isActive(mobile.sessionId())).isTrue();

        service.revoke(web.refreshToken()); // idempotent
        service.revoke(null);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
