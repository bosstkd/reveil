package com.reveil.event;

import java.util.Locale;
import java.util.Optional;

/** Filtre de la liste : événements à venir (date ≥ maintenant) ou passés (date < maintenant). */
public enum EventPeriod {
    UPCOMING,
    PAST;

    /** Valeur du paramètre {@code when} (« upcoming » / « past », insensible à la casse). */
    static Optional<EventPeriod> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
