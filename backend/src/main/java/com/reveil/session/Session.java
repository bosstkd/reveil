package com.reveil.session;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Session d'un appareil (navigateur, téléphone). Une connexion = une session.
 * Seul le hash SHA-256 du jeton de rafraîchissement est stocké.
 */
@Document(collection = "sessions")
public class Session {

    @Id
    private String id;

    @Indexed(name = "ix_sessions_user")
    private String userId;

    private String tokenHash;

    /** Jeton précédent, toléré pendant la fenêtre de rotation (requêtes concurrentes). */
    private String previousTokenHash;

    private Instant rotatedAt;

    private Instant createdAt;

    /** Expiration glissante ; MongoDB supprime le document une fois la date passée (index TTL). */
    @Indexed(name = "ttl_sessions_expires_at", expireAfter = "0s")
    private Instant expiresAt;

    @Version
    private Long version;

    protected Session() {
    }

    public Session(String id, String userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void rotate(String newTokenHash, Instant now, Instant newExpiresAt) {
        this.previousTokenHash = this.tokenHash;
        this.tokenHash = newTokenHash;
        this.rotatedAt = now;
        this.expiresAt = newExpiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getPreviousTokenHash() {
        return previousTokenHash;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
