package com.reveil.event;

import java.util.List;

/**
 * Page de la liste des événements.
 *
 * @param items         événements de la page (à venir : date croissante ; passés : du plus récent au plus ancien)
 * @param page          numéro de page (à partir de 0)
 * @param size          taille de page demandée
 * @param totalElements nombre total d'événements correspondant au filtre
 * @param hasNext       vrai s'il reste une page suivante
 */
public record EventPage(String when, List<EventResponse> items, int page, int size, long totalElements, boolean hasNext) {
}
