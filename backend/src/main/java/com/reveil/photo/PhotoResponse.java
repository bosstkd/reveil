package com.reveil.photo;

import java.time.Instant;

/**
 * Photo renvoyée par l'API. {@code url} / {@code thumbnailUrl} sont des chemins d'API protégés
 * (en-tête Authorization requis), relatifs à la racine de l'API.
 */
public record PhotoResponse(String id, String eventId, String contentType, long size, int width, int height,
                            Instant createdAt, String url, String thumbnailUrl) {

    static PhotoResponse of(Photo p) {
        String base = "/api/events/" + p.getEventId() + "/photos/" + p.getId();
        return new PhotoResponse(p.getId(), p.getEventId(), p.getContentType(), p.getSize(), p.getWidth(),
                p.getHeight(), p.getCreatedAt(), base + "/content", base + "/thumbnail");
    }
}
