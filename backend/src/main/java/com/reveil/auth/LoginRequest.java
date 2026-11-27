package com.reveil.auth;

/** Pas de validation par champ : toute erreur donne le même message générique (401). */
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
