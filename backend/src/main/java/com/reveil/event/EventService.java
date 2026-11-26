package com.reveil.event;

import com.reveil.reminder.ReminderSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository events;
    private final ReminderSettings reminders;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Autowired
    public EventService(EventRepository events, ReminderSettings reminders, ApplicationEventPublisher publisher) {
        this(events, reminders, publisher, Clock.systemUTC());
    }

    EventService(EventRepository events, ReminderSettings reminders, ApplicationEventPublisher publisher, Clock clock) {
        this.events = events;
        this.reminders = reminders;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Crée l'événement de {@code ownerId}. La date/heure locale est convertie en UTC selon le fuseau fourni
     * (heure inexistante lors d'un passage à l'heure d'été : décalée vers l'avant, règle java.time).
     * Une date passée est acceptée ; la réponse porte alors un avertissement.
     */
    public EventResponse create(String ownerId, CreateEventRequest request) {
        String zone = request.timeZone().trim();
        Instant startsAt = startsAt(request, zone);
        Instant now = clock.instant();
        Event event = new Event(request.title().trim(), startsAt, zone, location(request), ownerId);
        Instant dueAt = reminderDueAt(startsAt, now);
        if (dueAt != null) {
            event.scheduleReminder(dueAt);
        }
        Event saved = events.save(event);
        return EventResponse.of(saved, now);
    }

    /**
     * Remplace toutes les informations saisies de l'événement {@code id} (mêmes règles qu'à la création).
     * Si la date change, le rappel est recalculé sur la nouvelle date (y compris s'il avait déjà été envoyé) ;
     * sinon il est conservé tel quel. Inexistant : 404 ; appartenant à un autre utilisateur : 403.
     */
    public EventResponse update(String ownerId, String id, CreateEventRequest request) {
        Event current = requireOwned(ownerId, id);
        String zone = request.timeZone().trim();
        Instant startsAt = startsAt(request, zone);
        Instant now = clock.instant();
        boolean dateChanged = !startsAt.equals(current.getStartsAt());
        EventChanges changes = new EventChanges(request.title().trim(), startsAt, zone, location(request),
                dateChanged, dateChanged ? reminderDueAt(startsAt, now) : null);
        Event updated = events.applyChanges(id, ownerId, changes).orElseThrow(EventNotFoundException::new);
        return EventResponse.of(updated, now);
    }

    /**
     * Supprime l'événement {@code id} et, avec lui, son rappel programmé (porté par le document) ; les photos
     * rattachées sont supprimées via {@link EventDeleted}. Inexistant : 404 ; appartenant à un autre utilisateur : 403.
     */
    public void delete(String ownerId, String id) {
        requireOwned(ownerId, id);
        events.deleteById(id);
        publisher.publishEvent(new EventDeleted(id, ownerId));
    }

    /** Événement de {@code ownerId} ; introuvable (ou appartenant à un autre utilisateur) : 404. */
    public EventResponse get(String ownerId, String id) {
        return events.findByIdAndOwnerId(id, ownerId)
                .map(e -> EventResponse.of(e, clock.instant()))
                .orElseThrow(EventNotFoundException::new);
    }

    private Event requireOwned(String ownerId, String id) {
        Event event = events.findById(id).orElseThrow(EventNotFoundException::new);
        if (!event.getOwnerId().equals(ownerId)) {
            throw new EventAccessDeniedException();
        }
        return event;
    }

    private static Instant startsAt(CreateEventRequest request, String zone) {
        return ZonedDateTime.of(request.date(), request.time().withNano(0), ZoneId.of(zone)).toInstant();
    }

    private static String location(CreateEventRequest request) {
        return request.location() == null || request.location().isBlank() ? null : request.location().trim();
    }

    /**
     * Rappel par défaut (1 h avant). Événement passé : aucun rappel (null). Échéance déjà dépassée
     * (événement dans moins d'une heure) : le rappel part au prochain passage du planificateur.
     */
    private Instant reminderDueAt(Instant startsAt, Instant now) {
        return startsAt.isAfter(now) ? startsAt.minus(reminders.leadTime()) : null;
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
