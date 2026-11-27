package com.reveil.event;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Fragment Spring Data de {@link EventRepository} (suffixe « Impl »). */
class EventRepositoryImpl implements EventRepositoryCustom {

    private final MongoTemplate mongo;

    EventRepositoryImpl(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Event> applyChanges(String id, String ownerId, EventChanges changes) {
        Criteria criteria = where("_id").is(id).and("ownerId").is(ownerId);
        Update update = new Update()
                .set("title", changes.title())
                .set("startsAt", changes.startsAt())
                .set("timeZone", changes.timeZone());
        if (changes.location() == null) {
            update.unset("location");
        } else {
            update.set("location", changes.location());
        }
        if (changes.reminders() != null) {
            // Les rappels ne sont remplacés que s'ils n'ont pas été traités entre la lecture et l'écriture.
            criteria = changes.expectedVersion() == 0
                    ? criteria.orOperator(where("remindersVersion").is(0L), where("remindersVersion").exists(false))
                    : criteria.and("remindersVersion").is(changes.expectedVersion());
            update.set("reminders", changes.reminders())
                    .inc("remindersVersion", 1)
                    // Champs du rappel unique d'avant la story 9.
                    .unset("reminderDueAt")
                    .unset("reminderSentAt");
        }
        return Optional.ofNullable(mongo.findAndModify(Query.query(criteria), update,
                FindAndModifyOptions.options().returnNew(true), Event.class));
    }
}
