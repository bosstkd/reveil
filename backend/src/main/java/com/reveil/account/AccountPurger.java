package com.reveil.account;

import com.reveil.device.DeviceTokenRepository;
import com.reveil.event.EventRepository;
import com.reveil.photo.PhotoRepository;
import com.reveil.photo.PhotoStorage;
import com.reveil.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * Supprime toutes les données d'un utilisateur. Idempotent : peut être rejoué sans effet de bord
 * (passe de contrôle du planificateur, reprise après échec).
 */
@Component
public class AccountPurger {

    private final SessionRepository sessions;
    private final DeviceTokenRepository devices;
    private final EventRepository events;
    private final PhotoRepository photos;
    private final PhotoStorage storage;

    public AccountPurger(SessionRepository sessions, DeviceTokenRepository devices, EventRepository events,
                         PhotoRepository photos, PhotoStorage storage) {
        this.sessions = sessions;
        this.devices = devices;
        this.events = events;
        this.photos = photos;
        this.storage = storage;
    }

    public void purge(String userId) {
        // Ordre : d'abord ce qui permet d'agir ou de notifier (sessions, jetons d'appareils), puis le contenu.
        sessions.deleteByUserId(userId);
        devices.deleteByUserId(userId);
        // Les rappels sont portés par les documents d'événements : supprimés avec eux.
        events.deleteByOwnerId(userId);
        photos.deleteByOwnerId(userId);
        storage.deleteAllOwnedBy(userId);
    }
}
