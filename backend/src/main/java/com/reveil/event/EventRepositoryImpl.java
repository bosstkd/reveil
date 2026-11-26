package com.reveil.event;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
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
        Query query = Query.query(where("_id").is(id).and("ownerId").is(ownerId));
        Update update = new Update()
                .set("title", changes.title())
                .set("startsAt", changes.startsAt())
                .set("timeZone", changes.timeZone());
        if (changes.location() == null) {
            update.unset("location");
        } else {
            update.set("location", changes.location());
        }
        if (changes.rescheduleReminder()) {
            update.unset("reminderSentAt");
            if (changes.reminderDueAt() == null) {
                update.unset("reminderDueAt");
            } else {
                update.set("reminderDueAt", changes.reminderDueAt());
            }
        }
        return Optional.ofNullable(mongo.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Event.class));
    }
}
