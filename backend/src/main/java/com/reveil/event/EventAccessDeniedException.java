package com.reveil.event;

/** Événement existant mais appartenant à un autre utilisateur, visé par une modification ou une suppression : 403. */
public class EventAccessDeniedException extends RuntimeException {

    public EventAccessDeniedException() {
        super("Vous n'êtes pas autorisé à modifier cet événement");
    }
}
