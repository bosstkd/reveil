package com.reveil.event;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Événement renvoyé par l'API.
 *
 * @param startsAt      instant UTC (ISO-8601, suffixe Z)
 * @param localDateTime date/heure telle que saisie, dans {@code timeZone}
 * @param past          vrai si l'événement est déjà passé au moment de la réponse
 * @param warnings      avertissements non bloquants (ex. date passée)
 * @param reminders     rappels configurés (programmés ou déjà traités)
 */
public record EventResponse(String id, String title, Instant startsAt, LocalDateTime localDateTime, String timeZone,
                            String location, boolean past, List<String> warnings,
                            List<EventReminder> reminders) {

    public static final String PAST_WARNING = "La date de cet événement est déjà passée";

    static EventResponse of(Event e, Instant now, Duration leadTime) {
        boolean past = e.getStartsAt().isBefore(now);
        return new EventResponse(e.getId(), e.getTitle(), e.getStartsAt(),
                LocalDateTime.ofInstant(e.getStartsAt(), ZoneId.of(e.getTimeZone())), e.getTimeZone(),
                e.getLocation(), past, past ? List.of(PAST_WARNING) : List.of(),
                EventReminder.of(e, leadTime));
    }
}
