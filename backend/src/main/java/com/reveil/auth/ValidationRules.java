package com.reveil.auth;

/** Règles de validation partagées de l'inscription. */
final class ValidationRules {

    /** E-mail : partie locale, @, domaine avec au moins un point. */
    static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    private ValidationRules() {
    }
}
