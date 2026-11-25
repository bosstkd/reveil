package com.reveil.event;

import java.time.Instant;
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

    protected Event() {
    }

    public Event(String title, Instant startsAt, String timeZone, String location, String ownerId) {
        this.title = title;
        this.startsAt = startsAt;
        this.timeZone = timeZone;
        this.location = location;
        this.ownerId = ownerId;
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
}
