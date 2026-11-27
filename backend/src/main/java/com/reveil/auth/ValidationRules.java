package com.reveil.auth;

/** Règles de validation partagées (inscription, réinitialisation du mot de passe). */
public final class ValidationRules {

    /** E-mail : partie locale, @, domaine avec au moins un point. */
    public static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    private ValidationRules() {
    }
}
