package com.reveil.photo;

import com.reveil.event.EventDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Supprime les photos (métadonnées et fichiers GridFS) d'un événement supprimé. */
@Component
public class EventPhotosCleaner {

    private static final Logger log = LoggerFactory.getLogger(EventPhotosCleaner.class);

    private final PhotoRepository photos;
    private final PhotoStorage storage;

    public EventPhotosCleaner(PhotoRepository photos, PhotoStorage storage) {
        this.photos = photos;
        this.storage = storage;
    }

    @EventListener
    public void onEventDeleted(EventDeleted deleted) {
        try {
            photos.deleteByEventId(deleted.eventId());
            storage.deleteAllOf(deleted.eventId());
        } catch (RuntimeException e) {
            // L'événement est déjà supprimé : ses photos restent inaccessibles (contrôle de l'événement parent).
            log.error("Photos de l'événement {} non supprimées", deleted.eventId(), e);
        }
    }
}
