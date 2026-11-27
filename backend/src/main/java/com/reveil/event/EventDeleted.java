package com.reveil.event;

/**
 * Publié (événement applicatif Spring, synchrone) après la suppression d'un événement : les modules qui y
 * rattachent des données (photos) les suppriment à leur tour.
 */
public record EventDeleted(String eventId, String ownerId) {
}
