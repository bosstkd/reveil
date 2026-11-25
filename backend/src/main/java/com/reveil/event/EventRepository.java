package com.reveil.event;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Requêtes servies par l'index composé (ownerId, startsAt) : {@code ix_events_owner_starts_at}. */
public interface EventRepository extends MongoRepository<Event, String> {

    /** À venir : tri à fournir dans {@code pageable} (startsAt croissant). */
    Page<Event> findByOwnerIdAndStartsAtGreaterThanEqual(String ownerId, Instant from, Pageable pageable);

    /** Passés : tri à fournir dans {@code pageable} (startsAt décroissant). */
    Page<Event> findByOwnerIdAndStartsAtLessThan(String ownerId, Instant before, Pageable pageable);
}
