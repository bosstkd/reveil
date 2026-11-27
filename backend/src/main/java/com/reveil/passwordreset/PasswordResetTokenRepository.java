package com.reveil.passwordreset;

import java.time.Instant;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {

    /** Jetons encore valides de l'utilisateur (limitation des demandes). */
    long countByUserIdAndExpiresAtAfter(String userId, Instant now);

    /** Tous les jetons de l'utilisateur : après réinitialisation et suppression du compte. */
    long deleteByUserId(String userId);
}
