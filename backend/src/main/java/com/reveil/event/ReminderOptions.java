package com.reveil.event;

import java.util.Comparator;
import java.util.List;

/** Délais de rappel proposés (en minutes) : 15 min, 1 h, 1 jour, 1 semaine ; au plus {@link #MAX} par événement. */
public final class ReminderOptions {

    public static final List<Integer> ALLOWED = List.of(15, 60, 1440, 10080);
    public static final int MAX = 3;

    private ReminderOptions() {
    }

    /** Délais sans doublon, du plus éloigné au plus proche de l'événement. */
    static List<Integer> normalize(List<Integer> delays) {
        return delays.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    }
}
