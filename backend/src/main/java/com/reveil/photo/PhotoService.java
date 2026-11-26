package com.reveil.photo;

import com.reveil.event.EventNotFoundException;
import com.reveil.event.EventRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PhotoService {

    static final String EMPTY = "Aucune photo reçue";

    private final EventRepository events;
    private final PhotoRepository photos;
    private final PhotoStorage storage;
    private final ImageProcessor images;
    private final PhotoSettings settings;

    public PhotoService(EventRepository events, PhotoRepository photos, PhotoStorage storage, ImageProcessor images,
                        PhotoSettings settings) {
        this.events = events;
        this.photos = photos;
        this.storage = storage;
        this.images = images;
        this.settings = settings;
    }

    /**
     * Rattache la photo à l'événement {@code eventId} de {@code ownerId} : contrôle taille et type réel,
     * enregistre l'original et une miniature JPEG dans GridFS, puis les métadonnées.
     */
    public PhotoResponse upload(String ownerId, String eventId, MultipartFile file) {
        requireEvent(ownerId, eventId);
        if (file == null || file.isEmpty()) {
            throw new InvalidPhotoException(HttpStatus.BAD_REQUEST, EMPTY);
        }
        if (file.getSize() > settings.maxSize().toBytes()) {
            throw tooLarge(settings);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ImageProcessor.Processed image = images.process(data);
        String extension = image.contentType().equals("image/png") ? ".png" : ".jpg";
        String fileId = storage.store(data, "photo" + extension, image.contentType(), ownerId, eventId);
        String thumbId = null;
        try {
            thumbId = storage.store(image.thumbnail(), "thumbnail.jpg", "image/jpeg", ownerId, eventId);
            Photo saved = photos.save(new Photo(eventId, ownerId, fileId, thumbId, image.contentType(), data.length,
                    image.width(), image.height()));
            return PhotoResponse.of(saved);
        } catch (RuntimeException e) {
            // Pas de fichier orphelin si l'enregistrement échoue à mi-chemin.
            storage.delete(fileId);
            storage.delete(thumbId);
            throw e;
        }
    }

    /** Galerie de l'événement, de la plus récente à la plus ancienne. */
    public List<PhotoResponse> list(String ownerId, String eventId) {
        requireEvent(ownerId, eventId);
        return photos.findByEventIdAndOwnerIdOrderByCreatedAtDesc(eventId, ownerId).stream()
                .map(PhotoResponse::of)
                .toList();
    }

    /** Contenu de l'image (ou de sa miniature) d'une photo de l'utilisateur. */
    public Content content(String ownerId, String eventId, String photoId, boolean thumbnail) {
        Photo photo = photos.findByIdAndEventIdAndOwnerId(photoId, eventId, ownerId)
                .orElseThrow(PhotoNotFoundException::new);
        String fileId = thumbnail ? photo.getThumbnailFileId() : photo.getFileId();
        PhotoStorage.StoredFile file = storage.open(fileId).orElseThrow(PhotoNotFoundException::new);
        return new Content(file, thumbnail ? "image/jpeg" : photo.getContentType());
    }

    public record Content(PhotoStorage.StoredFile file, String contentType) {
    }

    static InvalidPhotoException tooLarge(PhotoSettings settings) {
        return new InvalidPhotoException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Photo trop volumineuse (maximum " + settings.maxSize().toMegabytes() + " Mo)");
    }

    private void requireEvent(String ownerId, String eventId) {
        if (events.findByIdAndOwnerId(eventId, ownerId).isEmpty()) {
            throw new EventNotFoundException();
        }
    }
}
