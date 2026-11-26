package com.reveil.event;

import java.time.Instant;

/**
 * Nouvelles valeurs d'un événement modifié.
 *
 * @param rescheduleReminder vrai si la date change : le rappel est alors recalculé ({@code reminderDueAt}
 *                           remplacé, {@code reminderSentAt} effacé) ; faux : les champs du rappel ne sont pas touchés
 * @param reminderDueAt      nouvelle échéance du rappel (null : aucun rappel, événement passé)
 */
public record EventChanges(String title, Instant startsAt, String timeZone, String location,
                           boolean rescheduleReminder, Instant reminderDueAt) {
}
