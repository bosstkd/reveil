package com.reveil.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Rappel configuré d'un événement, tel que renvoyé par l'API.
 *
 * @param minutesBefore délai entre le rappel et le début de l'événement, en minutes
 * @param remindAt      échéance du rappel (UTC)
 * @param status        {@code scheduled} (programmé), {@code sent} (envoyé) ou {@code skipped}
 *                      (abandonné : l'événement avait déjà commencé au moment du traitement)
 */
public record EventReminder(long minutesBefore, Instant remindAt, String status) {

    public enum Status {
        SCHEDULED, SENT, SKIPPED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Rappels de l'événement, reconstitués à partir de son document : programmé ({@code reminderDueAt}),
     * ou déjà traité ({@code reminderSentAt}, échéance théorique {@code startsAt − leadTime}).
     * Aucun rappel (événement créé dans le passé) : liste vide.
     */
    static List<EventReminder> of(Event e, Duration leadTime) {
        if (e.getReminderDueAt() != null) {
            return List.of(new EventReminder(Duration.between(e.getReminderDueAt(), e.getStartsAt()).toMinutes(),
                    e.getReminderDueAt(), Status.SCHEDULED.value()));
        }
        if (e.getReminderSentAt() != null) {
            Status status = e.getReminderSentAt().isBefore(e.getStartsAt()) ? Status.SENT : Status.SKIPPED;
            return List.of(new EventReminder(leadTime.toMinutes(), e.getStartsAt().minus(leadTime), status.value()));
        }
        return List.of();
    }
}
