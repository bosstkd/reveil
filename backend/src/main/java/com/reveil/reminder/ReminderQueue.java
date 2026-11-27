package com.reveil.reminder;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.reveil.event.Event;
import com.reveil.event.Reminder;
import com.reveil.event.ReminderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * File des rappels, portée par les événements eux-mêmes (tableau {@code reminders}, index multiclé
 * {@code ix_events_reminders_due}) : un événement supprimé n'a plus de rappel.
 */
@Component
public class ReminderQueue {

    private static final Logger log = LoggerFactory.getLogger(ReminderQueue.class);
    private static final String SCHEDULED = ReminderStatus.SCHEDULED.name();
    /** Index du rappel unique d'avant la story 9 (champ {@code reminderDueAt}). */
    static final String LEGACY_INDEX = "ix_events_reminder_due_at";

    private final MongoTemplate mongo;

    public ReminderQueue(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Au moins un rappel programmé d'échéance ≤ {@code dueBefore} (servi par {@code ix_events_reminders_due}). */
    private static Criteria hasDue(Instant dueBefore) {
        return where("reminders").elemMatch(where("status").is(SCHEDULED).and("dueAt").lte(Date.from(dueBefore)));
    }

    /** Marque {@code status} tous les rappels programmés échus de l'événement (filtre de tableau {@code r}). */
    private static Update markDue(Instant dueBefore, ReminderStatus status, Instant now) {
        return new Update()
                .set("reminders.$[r].status", status.name())
                .set("reminders.$[r].processedAt", Date.from(now))
                .inc("remindersVersion", 1)
                .filterArray(where("r.status").is(SCHEDULED).and("r.dueAt").lte(Date.from(dueBefore)));
    }

    /**
     * Abandonne les rappels échus des événements déjà commencés (API arrêtée au moment de l'échéance) :
     * un rappel n'a plus de sens. Renvoie le nombre d'événements concernés.
     */
    public long skipStarted(Instant dueBefore, Instant now) {
        Query query = Query.query(new Criteria().andOperator(hasDue(dueBefore), where("startsAt").lte(Date.from(now))));
        return mongo.updateMulti(query, markDue(dueBefore, ReminderStatus.SKIPPED, now), Event.class).getModifiedCount();
    }

    /**
     * Prend le prochain événement à venir ayant des rappels échus et marque ceux-ci envoyés, en une seule
     * opération atomique : plusieurs instances de l'API ne peuvent pas envoyer deux fois le même rappel.
     * Plusieurs rappels échus d'un même événement (créé ou déplacé peu avant) sont pris ensemble : une seule
     * notification. Renvoie l'événement après mise à jour.
     */
    public Optional<Event> claimNextDue(Instant dueBefore, Instant now) {
        Query query = Query.query(new Criteria().andOperator(hasDue(dueBefore), where("startsAt").gt(Date.from(now))));
        return Optional.ofNullable(mongo.findAndModify(query, markDue(dueBefore, ReminderStatus.SENT, now),
                FindAndModifyOptions.options().returnNew(true), Event.class));
    }

    /**
     * Convertit les événements d'avant la gestion des rappels multiples (pas de tableau {@code reminders}) :
     * <ul>
     *   <li>rappel unique programmé ({@code reminderDueAt}) : repris avec son délai ;</li>
     *   <li>rappel unique traité ({@code reminderSentAt}) : repris comme envoyé (ou abandonné) ;</li>
     *   <li>aucun rappel et événement à venir (créé avant la gestion des rappels) : délai par défaut ;</li>
     *   <li>sinon (événement passé) : aucun rappel.</li>
     * </ul>
     * Idempotent. Renvoie le nombre d'événements convertis.
     */
    public long migrateLegacy(Instant now, Duration defaultLeadTime) {
        dropLegacyIndex();
        int defaultMinutes = (int) defaultLeadTime.toMinutes();
        long migrated = 0;
        Query legacy = Query.query(where("reminders").exists(false));
        legacy.fields().include("_id", "startsAt", "reminderDueAt", "reminderSentAt");
        for (Document doc : mongo.find(legacy, Document.class, "events")) {
            Date starts = doc.getDate("startsAt");
            if (starts == null) {
                continue;
            }
            Instant startsAt = starts.toInstant();
            List<Reminder> reminders = legacyReminders(startsAt, doc.getDate("reminderDueAt"), doc.getDate("reminderSentAt"),
                    now, defaultMinutes);
            Update update = new Update().set("reminders", reminders).unset("reminderDueAt").unset("reminderSentAt");
            Query one = Query.query(where("_id").is(doc.get("_id")).and("reminders").exists(false));
            migrated += mongo.updateFirst(one, update, Event.class).getModifiedCount();
        }
        return migrated;
    }

    static List<Reminder> legacyReminders(Instant startsAt, Date dueAt, Date sentAt, Instant now, int defaultMinutes) {
        if (dueAt != null) {
            int minutes = (int) Duration.between(dueAt.toInstant(), startsAt).toMinutes();
            return List.of(new Reminder(minutes, dueAt.toInstant(), ReminderStatus.SCHEDULED, null));
        }
        if (sentAt != null) {
            ReminderStatus status = sentAt.toInstant().isBefore(startsAt) ? ReminderStatus.SENT : ReminderStatus.SKIPPED;
            return List.of(new Reminder(defaultMinutes, startsAt.minus(Duration.ofMinutes(defaultMinutes)), status,
                    sentAt.toInstant()));
        }
        return startsAt.isAfter(now) ? List.of(Reminder.scheduled(defaultMinutes, startsAt)) : List.of();
    }

    private void dropLegacyIndex() {
        try {
            boolean present = mongo.indexOps(Event.class).getIndexInfo().stream()
                    .anyMatch(ix -> LEGACY_INDEX.equals(ix.getName()));
            if (present) {
                mongo.indexOps(Event.class).dropIndex(LEGACY_INDEX);
            }
        } catch (RuntimeException e) {
            log.warn("Suppression de l'ancien index {} impossible", LEGACY_INDEX, e);
        }
    }
}
