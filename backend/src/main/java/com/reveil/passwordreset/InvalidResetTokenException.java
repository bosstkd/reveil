package com.reveil.passwordreset;

/** Lien de réinitialisation inconnu, expiré ou déjà utilisé (même message dans tous les cas). */
public class InvalidResetTokenException extends RuntimeException {

    public static final String MESSAGE = "Ce lien de réinitialisation est invalide ou a expiré";

    public InvalidResetTokenException() {
        super(MESSAGE);
    }
}
