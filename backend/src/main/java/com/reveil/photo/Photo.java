package com.reveil.photo;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Métadonnées d'une photo d'événement. Les octets (original et miniature) sont dans GridFS
 * (bucket {@code fs}) et référencés par {@link #fileId} / {@link #thumbnailFileId}.
 */
@Document(collection = "photos")
@CompoundIndex(name = "ix_photos_event_created_at", def = "{'eventId': 1, 'createdAt': -1}")
public class Photo {

    @Id
    private String id;

    private String eventId;

    /** Propriétaire (celui de l'événement). */
    private String ownerId;

    /** Identifiant GridFS de l'image envoyée. */
    private String fileId;

    /** Identifiant GridFS de la miniature JPEG. */
    private String thumbnailFileId;

    /** Type MIME constaté sur le contenu (image/jpeg ou image/png). */
    private String contentType;

    /** Taille en octets de l'image envoyée. */
    private long size;

    private int width;

    private int height;

    @CreatedDate
    private Instant createdAt;

    protected Photo() {
    }

    public Photo(String eventId, String ownerId, String fileId, String thumbnailFileId, String contentType, long size,
                 int width, int height) {
        this.eventId = eventId;
        this.ownerId = ownerId;
        this.fileId = fileId;
        this.thumbnailFileId = thumbnailFileId;
        this.contentType = contentType;
        this.size = size;
        this.width = width;
        this.height = height;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getFileId() {
        return fileId;
    }

    public String getThumbnailFileId() {
        return thumbnailFileId;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
