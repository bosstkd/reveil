package com.reveil.event;

import java.time.Instant;
import java.util.List;

/**
 * Rappel configuré d'un événement, tel que renvoyé par l'API.
 *
 * @param minutesBefore délai entre le rappel et le début de l'événement, en minutes
 * @param remindAt      échéance du rappel (UTC)
 * @param status        {@code scheduled} (programmé), {@code sent} (envoyé) ou {@code skipped}
 *                      (abandonné : l'événement avait déjà commencé au moment du traitement)
 */
public record EventReminder(long minutesBefore, Instant remindAt, String status) {

    /** Rappels de l'événement, du plus éloigné au plus proche ; aucun rappel : liste vide. */
    static List<EventReminder> of(Event e) {
        if (e.getReminders() == null) {
            return List.of();
        }
        return e.getReminders().stream()
                .sorted((a, b) -> Integer.compare(b.getMinutesBefore(), a.getMinutesBefore()))
                .map(r -> new EventReminder(r.getMinutesBefore(), r.getDueAt(), r.getStatus().value()))
                .toList();
    }
}
