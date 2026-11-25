package com.reveil.event;

/** Paramètre de liste invalide (filtre, page, taille) : 400 avec le champ en cause. */
public class InvalidListQueryException extends RuntimeException {

    private final String field;

    public InvalidListQueryException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
