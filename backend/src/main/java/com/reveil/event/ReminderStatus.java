package com.reveil.event;

import java.util.Locale;

/** État d'un rappel configuré (stocké sous son nom, renvoyé en minuscules par l'API). */
public enum ReminderStatus {
    /** Programmé : sera envoyé à son échéance. */
    SCHEDULED,
    /** Envoyé (une seule fois). */
    SENT,
    /** Abandonné : l'événement avait déjà commencé au moment du traitement. */
    SKIPPED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
