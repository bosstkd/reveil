package com.reveil.event;

import com.reveil.reminder.ReminderSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    /** Tentatives de modification en cas d'écriture concurrente des rappels par le planificateur. */
    private static final int MAX_UPDATE_ATTEMPTS = 3;

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
        List<Integer> delays = request.reminders() != null ? request.reminders() : defaultDelays();
        event.setReminders(planReminders(delays, startsAt, now, List.of()));
        Event saved = events.save(event);
        return EventResponse.of(saved, now);
    }

    /**
     * Remplace toutes les informations saisies de l'événement {@code id} (mêmes règles qu'à la création).
     * Si la date change, tous les rappels sont recalculés sur la nouvelle date (y compris ceux déjà envoyés).
     * Sinon, un rappel dont le délai est conservé garde son état (un rappel envoyé ne repart pas) ; seuls les
     * délais ajoutés sont programmés. {@code reminders} absent : délais actuels conservés.
     * Inexistant : 404 ; appartenant à un autre utilisateur : 403.
     */
    public EventResponse update(String ownerId, String id, CreateEventRequest request) {
        String zone = request.timeZone().trim();
        Instant startsAt = startsAt(request, zone);
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            Event current = requireOwned(ownerId, id);
            Instant now = clock.instant();
            List<Reminder> existing = current.getReminders() == null ? List.of() : current.getReminders();
            boolean dateChanged = !startsAt.equals(current.getStartsAt());
            List<Integer> delays = request.reminders() != null ? request.reminders()
                    : current.getReminders() == null ? defaultDelays()
                    : existing.stream().map(Reminder::getMinutesBefore).toList();
            List<Reminder> planned = planReminders(delays, startsAt, now, dateChanged ? List.of() : existing);
            boolean remindersChanged = current.getReminders() == null || !sameReminders(planned, existing);
            EventChanges changes = new EventChanges(request.title().trim(), startsAt, zone, location(request),
                    remindersChanged ? planned : null, current.getRemindersVersion());
            Optional<Event> updated = events.applyChanges(id, ownerId, changes);
            if (updated.isPresent()) {
                return EventResponse.of(updated.get(), now);
            }
            // Supprimé entre-temps (404 au tour suivant) ou rappels traités par le planificateur : on recalcule.
        }
        throw new IllegalStateException("Modification concurrente persistante de l'événement " + id);
    }

    /** Délai par défaut ({@code reveil.reminders.lead-time}, 1 h). */
    private List<Integer> defaultDelays() {
        return List.of((int) reminders.leadTime().toMinutes());
    }

    /**
     * Rappels pour les délais {@code delays} d'un événement commençant à {@code startsAt}. Un délai déjà présent
     * dans {@code existing} est repris tel quel (état conservé). Événement passé : aucun nouveau rappel.
     * Échéance déjà dépassée d'un événement à venir : le rappel part au prochain passage du planificateur
     * (les rappels échus d'un même événement sont regroupés en une seule notification).
     */
    static List<Reminder> planReminders(List<Integer> delays, Instant startsAt, Instant now, List<Reminder> existing) {
        boolean upcoming = startsAt.isAfter(now);
        List<Reminder> result = new ArrayList<>();
        for (int minutes : ReminderOptions.normalize(delays)) {
            existing.stream().filter(r -> r.getMinutesBefore() == minutes).findFirst()
                    .or(() -> upcoming ? Optional.of(Reminder.scheduled(minutes, startsAt)) : Optional.empty())
                    .ifPresent(result::add);
        }
        return result;
    }

    private static boolean sameReminders(List<Reminder> a, List<Reminder> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Reminder r : a) {
            if (!b.contains(r)) {
                return false;
            }
        }
        return true;
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

    /**
     * Détail de l'événement de {@code ownerId}, avec ses rappels configurés (temps restant calculé par les clients
     * à partir de {@code startsAt}). Introuvable (ou appartenant à un autre utilisateur) : 404.
     */
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
