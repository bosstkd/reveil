package com.reveil.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    /**
     * Page des événements de {@code ownerId} : à venir triés du plus proche au plus lointain,
     * passés du plus récent au plus ancien. « Maintenant » est l'horloge du serveur (UTC).
     */
    public EventPage list(String ownerId, EventPeriod period, int page, int size) {
        Instant now = clock.instant();
        Page<Event> result = period == EventPeriod.UPCOMING
                ? events.findByOwnerIdAndStartsAtGreaterThanEqual(ownerId, now,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt")))
                : events.findByOwnerIdAndStartsAtLessThan(ownerId, now,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt")));
        List<EventResponse> items = result.getContent().stream().map(e -> EventResponse.of(e, now)).toList();
        return new EventPage(period.name().toLowerCase(Locale.ROOT), items, page, size, result.getTotalElements(),
                result.hasNext());
    }
}
