package com.reveil.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository events;
    private final Clock clock;

    @Autowired
    public EventService(EventRepository events) {
        this(events, Clock.systemUTC());
    }

    EventService(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Crée l'événement de {@code ownerId}. La date/heure locale est convertie en UTC selon le fuseau fourni
     * (heure inexistante lors d'un passage à l'heure d'été : décalée vers l'avant, règle java.time).
     * Une date passée est acceptée ; la réponse porte alors un avertissement.
     */
    public EventResponse create(String ownerId, CreateEventRequest request) {
        String zone = request.timeZone().trim();
        Instant startsAt = ZonedDateTime.of(request.date(), request.time().withNano(0), ZoneId.of(zone)).toInstant();
        String location = request.location() == null || request.location().isBlank() ? null : request.location().trim();
        Event saved = events.save(new Event(request.title().trim(), startsAt, zone, location, ownerId));
        return EventResponse.of(saved, clock.instant());
    }

    /** Événements de l'utilisateur, du plus proche au plus lointain. */
    public List<EventResponse> list(String ownerId) {
        Instant now = clock.instant();
        return events.findByOwnerIdOrderByStartsAtAsc(ownerId).stream().map(e -> EventResponse.of(e, now)).toList();
    }
}
