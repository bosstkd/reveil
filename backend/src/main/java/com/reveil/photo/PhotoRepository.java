package com.reveil.photo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Requêtes servies par l'index {@code ix_photos_event_created_at}. */
public interface PhotoRepository extends MongoRepository<Photo, String> {

    /** Galerie d'un événement, de la plus récente à la plus ancienne. */
    List<Photo> findByEventIdAndOwnerIdOrderByCreatedAtDesc(String eventId, String ownerId);

    Optional<Photo> findByIdAndEventIdAndOwnerId(String id, String eventId, String ownerId);

    long deleteByEventId(String eventId);

    /**
     * Retire atomiquement (findAndRemove) la photo de l'utilisateur et la renvoie : deux suppressions
     * concurrentes ne peuvent pas toutes deux réussir.
     */
    Optional<Photo> deleteByIdAndEventIdAndOwnerId(String id, String eventId, String ownerId);
}
