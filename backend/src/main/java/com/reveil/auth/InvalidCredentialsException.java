package com.reveil.auth;

/** Identifiants invalides. Message volontairement générique : ne révèle pas le champ en cause. */
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "E-mail ou mot de passe incorrect";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
