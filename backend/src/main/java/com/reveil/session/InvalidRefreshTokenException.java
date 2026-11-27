package com.reveil.session;

/** Jeton de rafraîchissement inconnu, expiré, révoqué ou rejoué. */
public class InvalidRefreshTokenException extends RuntimeException {

    public static final String MESSAGE = "Session expirée, veuillez vous reconnecter";

    public InvalidRefreshTokenException() {
        super(MESSAGE);
    }
}
