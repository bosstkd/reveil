package com.reveil.account;

/** Mot de passe de confirmation refusé (403 : l'utilisateur est authentifié, la confirmation échoue). */
public class IncorrectPasswordException extends RuntimeException {

    public static final String MESSAGE = "Mot de passe incorrect";

    public IncorrectPasswordException() {
        super(MESSAGE);
    }
}
