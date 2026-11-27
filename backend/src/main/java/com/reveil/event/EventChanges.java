package com.reveil.event;

import java.time.Instant;
import java.util.List;

/**
 * Nouvelles valeurs d'un événement modifié.
 *
 * @param reminders       nouveaux rappels (null : rappels inchangés, non écrits)
 * @param expectedVersion version des rappels lue avant le calcul ({@code remindersVersion}) : les rappels ne sont
 *                        écrits que si le planificateur ne les a pas modifiés entre-temps (sinon : aucune écriture)
 */
public record EventChanges(String title, Instant startsAt, String timeZone, String location,
                           List<Reminder> reminders, long expectedVersion) {
}
