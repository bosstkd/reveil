package com.reveil.event;

import java.util.Optional;

/** Mises à jour partielles et atomiques des événements (implémentation : {@link EventRepositoryImpl}). */
public interface EventRepositoryCustom {

    /**
     * Applique {@code changes} à l'événement {@code id} de {@code ownerId} en une seule opération et renvoie
     * l'événement modifié (vide s'il n'existe plus, ou si ses rappels ont changé depuis la lecture :
     * {@link EventChanges#expectedVersion()}). Seuls les champs modifiables sont écrits ; les rappels seulement si
     * {@link EventChanges#reminders()} est renseigné.
     */
    Optional<Event> applyChanges(String id, String ownerId, EventChanges changes);
}
