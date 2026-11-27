package com.reveil.reminder;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * Compteurs sur une fenêtre glissante, découpée en {@code slots} tranches (60 tranches d'1 min pour 1 h) :
 * les tranches plus anciennes que la fenêtre sont ignorées puis réutilisées. Mémoire constante, thread-safe.
 */
final class SlidingWindow {

    private final Clock clock;
    private final long slotMillis;
    private final int slots;
    private final long[] slotIds;
    private final long[][] counts;

    SlidingWindow(Clock clock, Duration window, int slots, int kinds) {
        if (window.toMillis() < slots) {
            throw new IllegalArgumentException("Fenêtre trop courte : " + window);
        }
        this.clock = clock;
        this.slots = slots;
        this.slotMillis = window.toMillis() / slots;
        this.slotIds = new long[slots];
        Arrays.fill(slotIds, Long.MIN_VALUE);
        this.counts = new long[slots][kinds];
    }

    synchronized void add(int kind, long n) {
        long id = clock.millis() / slotMillis;
        int i = (int) Math.floorMod(id, (long) slots);
        if (slotIds[i] != id) {
            slotIds[i] = id;
            Arrays.fill(counts[i], 0);
        }
        counts[i][kind] += n;
    }

    /** Totaux par type sur la fenêtre courante. */
    synchronized long[] totals() {
        long current = clock.millis() / slotMillis;
        long[] totals = new long[counts[0].length];
        for (int i = 0; i < slots; i++) {
            if (slotIds[i] > current - slots && slotIds[i] <= current) {
                for (int k = 0; k < totals.length; k++) {
                    totals[k] += counts[i][k];
                }
            }
        }
        return totals;
    }
}
