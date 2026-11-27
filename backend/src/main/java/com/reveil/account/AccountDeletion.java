package com.reveil.account;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Demande de suppression de compte en cours (collection {@code account_deletions}). Seul l'identifiant de
 * l'utilisateur est conservé (aucune donnée personnelle) ; le document disparaît une fois la purge confirmée.
 */
@Document(collection = "account_deletions")
public class AccountDeletion {

    /** Identifiant de l'utilisateur supprimé (une demande par compte : idempotent). */
    @Id
    private String userId;

    @Indexed(name = "ix_account_deletions_requested_at")
    private Instant requestedAt;

    /** Échéance d'engagement : toutes les données doivent avoir disparu avant cette date (24 h). */
    private Instant deadline;

    protected AccountDeletion() {
    }

    public AccountDeletion(String userId, Instant requestedAt, Instant deadline) {
        this.userId = userId;
        this.requestedAt = requestedAt;
        this.deadline = deadline;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDeadline() {
        return deadline;
    }
}
