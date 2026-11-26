package com.reveil.event;

import java.util.Optional;

/** Mises à jour partielles et atomiques des événements (implémentation : {@link EventRepositoryImpl}). */
public interface EventRepositoryCustom {

    /**
     * Applique {@code changes} à l'événement {@code id} de {@code ownerId} en une seule opération et renvoie
     * l'événement modifié (vide s'il n'existe plus). Seuls les champs modifiables sont écrits : un rappel pris
     * en charge par le planificateur au même moment n'est pas reprogrammé si la date ne change pas.
     */
    Optional<Event> applyChanges(String id, String ownerId, EventChanges changes);
}
