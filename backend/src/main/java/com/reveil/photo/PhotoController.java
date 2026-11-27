package com.reveil.photo;

import com.reveil.auth.AuthenticatedUser;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Photos d'un événement de l'utilisateur connecté (routes protégées par SecurityConfig). */
@RestController
@RequestMapping("/api/events/{eventId}/photos")
public class PhotoController {

    /** Le contenu d'une photo ne change jamais : mis en cache côté client (privé, jeton requis). */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(30)).cachePrivate();

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    /** {@code POST} multipart, partie {@code file} (JPEG ou PNG). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse upload(@PathVariable String eventId, @RequestPart(name = "file", required = false) MultipartFile file,
                                @AuthenticationPrincipal AuthenticatedUser current) {
        return photoService.upload(current.id(), eventId, file);
    }

    /**
     * {@code POST} multipart, parties {@code files} (1 à 10 fichiers JPEG ou PNG) : chaque fichier est traité
     * indépendamment ; {@code 200} avec les photos enregistrées et les fichiers refusés.
     */
    @PostMapping(path = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchUploadResponse uploadAll(@PathVariable String eventId,
                                         @RequestPart(name = "files", required = false) List<MultipartFile> files,
                                         @AuthenticationPrincipal AuthenticatedUser current) {
        return photoService.uploadAll(current.id(), eventId, files);
    }

    @GetMapping
    public List<PhotoResponse> list(@PathVariable String eventId, @AuthenticationPrincipal AuthenticatedUser current) {
        return photoService.list(current.id(), eventId);
    }

    @GetMapping("/{photoId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String eventId, @PathVariable String photoId,
                                                       @AuthenticationPrincipal AuthenticatedUser current) {
        return serve(photoService.content(current.id(), eventId, photoId, false));
    }

    @GetMapping("/{photoId}/thumbnail")
    public ResponseEntity<InputStreamResource> thumbnail(@PathVariable String eventId, @PathVariable String photoId,
                                                         @AuthenticationPrincipal AuthenticatedUser current) {
        return serve(photoService.content(current.id(), eventId, photoId, true));
    }

    private static ResponseEntity<InputStreamResource> serve(PhotoService.Content content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.file().length())
                .cacheControl(IMMUTABLE)
                .body(new InputStreamResource(content.file().content()));
    }
}
