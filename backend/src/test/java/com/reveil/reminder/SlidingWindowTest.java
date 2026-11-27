package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {

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

    @Test
    void countsWithinTheWindowAndForgetsOlderSlots() {
        SlidingWindow window = new SlidingWindow(clock, Duration.ofHours(1), 60, 2);
        window.add(0, 3);
        now.set(now.get().plus(Duration.ofMinutes(30)));
        window.add(0, 1);
        window.add(1, 2);
        assertThat(window.totals()).containsExactly(4, 2);

        now.set(now.get().plus(Duration.ofMinutes(31))); // premier ajout sorti de la fenêtre
        assertThat(window.totals()).containsExactly(1, 2);

        now.set(now.get().plus(Duration.ofMinutes(30))); // tout est sorti
        assertThat(window.totals()).containsExactly(0, 0);
        window.add(1, 5); // tranche réutilisée, remise à zéro
        assertThat(window.totals()).containsExactly(0, 5);
    }
}
