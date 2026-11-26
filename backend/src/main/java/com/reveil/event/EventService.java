package com.reveil.event;

import com.reveil.reminder.ReminderSettings;
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
    private final ReminderSettings reminders;
    private final Clock clock;

    @Autowired
    public EventService(EventRepository events, ReminderSettings reminders) {
        this(events, reminders, Clock.systemUTC());
    }

    EventService(EventRepository events, ReminderSettings reminders, Clock clock) {
        this.events = events;
        this.reminders = reminders;
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
        Instant now = clock.instant();
        Event event = new Event(request.title().trim(), startsAt, zone, location, ownerId);
        // Rappel par défaut (1 h avant). Événement passé : aucun rappel. Échéance déjà dépassée
        // (événement dans moins d'une heure) : le rappel part au prochain passage du planificateur.
        if (startsAt.isAfter(now)) {
            event.scheduleReminder(startsAt.minus(reminders.leadTime()));
        }
        Event saved = events.save(event);
        return EventResponse.of(saved, now);
    }

    /** Événement de {@code ownerId} ; introuvable (ou appartenant à un autre utilisateur) : 404. */
    public EventResponse get(String ownerId, String id) {
        return events.findByIdAndOwnerId(id, ownerId)
                .map(e -> EventResponse.of(e, clock.instant()))
                .orElseThrow(EventNotFoundException::new);
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
