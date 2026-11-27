package com.reveil.event;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Événement d'un utilisateur. L'instant est stocké en UTC ({@link #startsAt}) avec le fuseau IANA
 * dans lequel l'utilisateur l'a saisi ({@link #timeZone}) : l'heure locale d'origine reste reconstituable.
 */
@Document(collection = "events")
@CompoundIndex(name = "ix_events_owner_starts_at", def = "{'ownerId': 1, 'startsAt': 1}")
// Rappels programmés échus : {reminders: {$elemMatch: {status: "SCHEDULED", dueAt: {$lte: …}}}} (index multiclé).
@CompoundIndex(name = "ix_events_reminders_due", def = "{'reminders.status': 1, 'reminders.dueAt': 1}")
public class Event {

    @Id
    private String id;

    private String title;

    /** Date/heure de l'événement, en UTC. */
    private Instant startsAt;

    /** Fuseau horaire IANA de l'utilisateur à la création (ex. « Europe/Paris »). */
    private String timeZone;

    /** Lieu facultatif (null si non renseigné). */
    private String location;

    /** Identifiant de l'utilisateur propriétaire. */
    private String ownerId;

    @CreatedDate
    private Instant createdAt;

    /**
     * Rappels configurés (au plus 3), du plus éloigné au plus proche. Null : document antérieur à la gestion des
     * rappels multiples (converti au démarrage, voir {@code ReminderQueue#migrateLegacy}).
     */
    private List<Reminder> reminders;

    /** Incrémenté à chaque écriture des rappels (planificateur, modification) : contrôle de concurrence optimiste. */
    private long remindersVersion;

    protected Event() {
    }

    public Event(String title, Instant startsAt, String timeZone, String location, String ownerId) {
        this.title = title;
        this.startsAt = startsAt;
        this.timeZone = timeZone;
        this.location = location;
        this.ownerId = ownerId;
    }

    public void setReminders(List<Reminder> reminders) {
        this.reminders = reminders;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getLocation() {
        return location;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Reminder> getReminders() {
        return reminders;
    }

    public long getRemindersVersion() {
        return remindersVersion;
    }
}
