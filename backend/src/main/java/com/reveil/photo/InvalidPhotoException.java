package com.reveil.photo;

import org.springframework.http.HttpStatus;

/** Fichier refusé : absent ou vide (400), type non pris en charge (415), trop volumineux (413). */
public class InvalidPhotoException extends RuntimeException {

    private final HttpStatus status;

    public InvalidPhotoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
