package com.reveil.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Rappel configuré d'un événement, stocké dans son document (champ {@code reminders}) : supprimer l'événement
 * supprime ses rappels. Les rappels programmés sont trouvés par l'index {@code ix_events_reminders_due}.
 */
public class Reminder {

    /** Délai entre le rappel et le début de l'événement, en minutes (parmi {@link ReminderOptions#ALLOWED}). */
    private int minutesBefore;

    /** Échéance (UTC) : début de l'événement − {@link #minutesBefore}. */
    private Instant dueAt;

    private ReminderStatus status;

    /** Date de traitement (envoi ou abandon) ; null tant que le rappel est programmé. */
    private Instant processedAt;

    protected Reminder() {
    }

    public Reminder(int minutesBefore, Instant dueAt, ReminderStatus status, Instant processedAt) {
        this.minutesBefore = minutesBefore;
        this.dueAt = dueAt;
        this.status = status;
        this.processedAt = processedAt;
    }

    /** Rappel programmé {@code minutesBefore} minutes avant {@code startsAt}. */
    public static Reminder scheduled(int minutesBefore, Instant startsAt) {
        return new Reminder(minutesBefore, startsAt.minusSeconds(60L * minutesBefore), ReminderStatus.SCHEDULED, null);
    }

    public int getMinutesBefore() {
        return minutesBefore;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Reminder r && minutesBefore == r.minutesBefore && Objects.equals(dueAt, r.dueAt)
                && status == r.status && Objects.equals(processedAt, r.processedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minutesBefore, dueAt, status, processedAt);
    }
}
