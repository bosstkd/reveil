package com.reveil.photo;

import java.util.List;

/**
 * Résultat d'un envoi multiple : chaque fichier est traité indépendamment. {@code photos} contient les photos
 * enregistrées (dans l'ordre d'envoi), {@code rejected} les fichiers refusés avec le motif.
 */
public record BatchUploadResponse(List<PhotoResponse> photos, List<RejectedFile> rejected) {

    /**
     * Fichier refusé.
     *
     * @param index    position du fichier dans l'envoi (à partir de 0)
     * @param filename nom déclaré par le client (tronqué à 255 caractères ; peut être {@code null})
     * @param status   statut HTTP qu'aurait reçu un envoi unitaire (400, 413, 415, 500)
     * @param message  motif, en français
     */
    public record RejectedFile(int index, String filename, int status, String message) {
    }
}
