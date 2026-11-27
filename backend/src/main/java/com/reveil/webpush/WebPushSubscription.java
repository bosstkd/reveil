package com.reveil.webpush;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Abonnement Web Push d'un navigateur (story 14). Une adresse ({@code endpoint}) = un navigateur : unique,
 * rattachée au dernier utilisateur / à la dernière session qui l'a enregistrée.
 */
@Document(collection = WebPushSubscription.COLLECTION)
public class WebPushSubscription {

    public static final String COLLECTION = "web_push_subscriptions";

    @Id
    private String id;

    /** Adresse fournie par le service push du navigateur (FCM, Mozilla, Apple, Microsoft). */
    @Indexed(unique = true, name = "uk_web_push_endpoint")
    private String endpoint;

    /** Clé publique ECDH P-256 du navigateur (base64url). */
    private String p256dh;

    /** Secret d'authentification du navigateur (base64url, 16 octets). */
    private String auth;

    @Indexed(name = "ix_web_push_user")
    private String userId;

    /** Session web de l'abonnement : une fois révoquée ou expirée (déconnexion), il n'est plus utilisé. */
    private String sessionId;

    private Instant createdAt;

    private Instant updatedAt;

    protected WebPushSubscription() {
    }

    public WebPushSubscription(String endpoint, String p256dh, String auth, String userId, String sessionId, Instant now) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userId = userId;
        this.sessionId = sessionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
