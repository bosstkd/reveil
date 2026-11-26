package com.reveil.reminder;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.reveil.event.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * File des rappels, portée par les événements eux-mêmes (champ {@code reminderDueAt}, index
 * {@code ix_events_reminder_due_at}) : un événement supprimé n'a plus de rappel.
 */
@Component
public class ReminderQueue {

    private final MongoTemplate mongo;

    public ReminderQueue(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Prend le prochain rappel échu ({@code reminderDueAt ≤ dueBefore}) et le marque traité, en une seule
     * opération atomique : plusieurs instances de l'API ne peuvent pas envoyer deux fois le même rappel.
     */
    public Optional<Event> claimNextDue(Instant dueBefore, Instant now) {
        Query query = Query.query(where("reminderDueAt").lte(dueBefore)).with(Sort.by("reminderDueAt"));
        Update update = new Update().unset("reminderDueAt").set("reminderSentAt", now);
        return Optional.ofNullable(mongo.findAndModify(query, update, Event.class));
    }

    /**
     * Programme le rappel des événements à venir créés avant la gestion des rappels
     * (ni échéance ni traitement enregistrés). Renvoie le nombre d'événements mis à jour.
     */
    public long scheduleMissing(Instant now, Duration leadTime) {
        Query query = Query.query(where("startsAt").gt(now)
                .and("reminderDueAt").exists(false)
                .and("reminderSentAt").exists(false));
        AggregationUpdate update = AggregationUpdate.update()
                .set("reminderDueAt").toValue(ArithmeticOperators.valueOf("startsAt").subtract(leadTime.toMillis()));
        return mongo.updateMulti(query, update, Event.class).getModifiedCount();
    }
}
