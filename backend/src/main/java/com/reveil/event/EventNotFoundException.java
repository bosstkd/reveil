package com.reveil.event;

/** Événement inexistant, supprimé ou appartenant à un autre utilisateur : 404 (sans distinguer les cas). */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException() {
        super("Événement introuvable");
    }
}
