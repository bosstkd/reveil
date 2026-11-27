package com.reveil.photo;

import com.reveil.event.EventNotFoundException;
import com.reveil.event.EventRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PhotoService {

    static final String EMPTY = "Aucune photo reçue";
    static final String STORE_FAILED = "Enregistrement de la photo impossible, réessayez";

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);
    private static final int MAX_FILENAME = 255;

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
        return store(ownerId, eventId, file);
    }

    /**
     * Envoi multiple (au plus {@code maxBatchFiles} fichiers) : chaque fichier est contrôlé et enregistré
     * indépendamment ; un fichier refusé n'empêche pas l'enregistrement des autres.
     */
    public BatchUploadResponse uploadAll(String ownerId, String eventId, List<MultipartFile> files) {
        requireEvent(ownerId, eventId);
        List<MultipartFile> received = files == null ? List.of() : files;
        if (received.isEmpty()) {
            throw new InvalidPhotoException(HttpStatus.BAD_REQUEST, EMPTY);
        }
        if (received.size() > settings.maxBatchFiles()) {
            throw new InvalidPhotoException(HttpStatus.BAD_REQUEST,
                    "Trop de photos : " + settings.maxBatchFiles() + " au maximum par envoi");
        }
        List<PhotoResponse> saved = new ArrayList<>();
        List<BatchUploadResponse.RejectedFile> rejected = new ArrayList<>();
        for (int i = 0; i < received.size(); i++) {
            MultipartFile file = received.get(i);
            try {
                saved.add(store(ownerId, eventId, file));
            } catch (InvalidPhotoException e) {
                rejected.add(new BatchUploadResponse.RejectedFile(i, filename(file), e.getStatus().value(), e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Photo {} de l'événement {} non enregistrée", i, eventId, e);
                rejected.add(new BatchUploadResponse.RejectedFile(i, filename(file),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), STORE_FAILED));
            }
        }
        return new BatchUploadResponse(saved, rejected);
    }

    private static String filename(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.length() > MAX_FILENAME ? name.substring(0, MAX_FILENAME) : name;
    }

    /** Contrôle et enregistre un fichier (l'événement a déjà été vérifié). */
    private PhotoResponse store(String ownerId, String eventId, MultipartFile file) {
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
