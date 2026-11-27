package com.reveil.account;

import com.reveil.device.DeviceTokenRepository;
import com.reveil.event.EventRepository;
import com.reveil.passwordreset.PasswordResetTokenRepository;
import com.reveil.photo.PhotoRepository;
import com.reveil.photo.PhotoStorage;
import com.reveil.session.SessionRepository;
import com.reveil.webpush.WebPushSubscriptionRepository;
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
    private final PasswordResetTokenRepository resetTokens;
    private final WebPushSubscriptionRepository webPushSubscriptions;

    public AccountPurger(SessionRepository sessions, DeviceTokenRepository devices, EventRepository events,
                         PhotoRepository photos, PhotoStorage storage, PasswordResetTokenRepository resetTokens,
                         WebPushSubscriptionRepository webPushSubscriptions) {
        this.sessions = sessions;
        this.devices = devices;
        this.events = events;
        this.photos = photos;
        this.storage = storage;
        this.resetTokens = resetTokens;
        this.webPushSubscriptions = webPushSubscriptions;
    }

    public void purge(String userId) {
        // Ordre : d'abord ce qui permet d'agir ou de notifier (sessions, jetons d'appareils, abonnements Web Push), puis le contenu.
        sessions.deleteByUserId(userId);
        resetTokens.deleteByUserId(userId);
        devices.deleteByUserId(userId);
        webPushSubscriptions.deleteByUserId(userId);
        // Les rappels sont portés par les documents d'événements : supprimés avec eux.
        events.deleteByOwnerId(userId);
        photos.deleteByOwnerId(userId);
        storage.deleteAllOwnedBy(userId);
    }
}
