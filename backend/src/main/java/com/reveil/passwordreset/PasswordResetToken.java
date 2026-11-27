package com.reveil.passwordreset;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Jeton de réinitialisation du mot de passe (collection {@code password_reset_tokens}). Seul le hash SHA-256 du
 * jeton est stocké (identifiant du document) : une fuite de la base ne permet pas de réinitialiser un compte.
 * Usage unique : le document est retiré atomiquement à l'utilisation.
 */
@Document(collection = "password_reset_tokens")
public class PasswordResetToken {

    /** Hash SHA-256 (hexadécimal) du jeton envoyé par e-mail. */
    @Id
    private String tokenHash;

    @Indexed(name = "ix_password_reset_tokens_user")
    private String userId;

    private Instant createdAt;

    /** Expiration (1 h) ; vérifiée à l'utilisation, MongoDB supprime ensuite le document (index TTL). */
    @Indexed(name = "ttl_password_reset_tokens_expires_at", expireAfter = "0s")
    private Instant expiresAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(String tokenHash, String userId, Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
