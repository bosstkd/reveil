package com.reveil.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.event.EventRepository;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Envoi, stockage GridFS, miniature et galerie de bout en bout (MongoDB embarqué). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    EventRepository events;
    @Autowired
    PhotoRepository photos;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clean() {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        photos.deleteAll();
        mongo.getCollection("fs.files").deleteMany(new Document());
        mongo.getCollection("fs.chunks").deleteMany(new Document());
    }

    private String tokenFor(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("token").asText();
    }

    private String createEvent(String token) throws Exception {
        String body = mvc.perform(post("/api/events").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"2099-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private static MockMultipartFile file(byte[] data, String type) {
        return new MockMultipartFile("file", "photo", type, data);
    }

    @Test
    void uploadedPhotoIsAttachedToTheEventAndAppearsInItsGallery() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        byte[] jpeg = TestImages.jpeg(2048, 1536);

        String body = mvc.perform(multipart("/api/events/" + eventId + "/photos").file(file(jpeg, "image/jpeg"))
                        .header("Authorization", alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.width").value(2048))
                .andExpect(jsonPath("$.height").value(1536))
                .andReturn().getResponse().getContentAsString();
        JsonNode photo = json.readTree(body);

        // Métadonnées en base, fichiers dans GridFS (original + miniature).
        Document meta = mongo.getCollection("photos").find().first();
        assertThat(meta).isNotNull();
        assertThat(meta.getString("eventId")).isEqualTo(eventId);
        assertThat(meta.getString("ownerId")).isEqualTo(users.findByEmail("alice@example.com").orElseThrow().getId());
        assertThat(mongo.getCollection("fs.files").countDocuments()).isEqualTo(2);

        mvc.perform(get("/api/events/" + eventId + "/photos").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(photo.get("id").asText()));

        byte[] original = mvc.perform(get(photo.get("url").asText()).header("Authorization", alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(jpeg);

        byte[] thumbnail = mvc.perform(get(photo.get("thumbnailUrl").asText()).header("Authorization", alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(thumb.getWidth()).isEqualTo(400);
        assertThat(thumb.getHeight()).isEqualTo(300);
    }

    @Test
    void galleryListsTheMostRecentPhotoFirst() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/api/events/" + eventId + "/photos")
                            .file(file(TestImages.jpeg(100 + i, 100), "image/jpeg")).header("Authorization", alice))
                    .andExpect(status().isCreated());
            Thread.sleep(5);
        }
        mvc.perform(get("/api/events/" + eventId + "/photos").header("Authorization", alice))
                .andExpect(jsonPath("$[0].width").value(101))
                .andExpect(jsonPath("$[1].width").value(100));
    }

    @Test
    void anotherUserCanNeitherUploadNorSeeThePhotos() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String eventId = createEvent(alice);
        String body = mvc.perform(multipart("/api/events/" + eventId + "/photos")
                        .file(file(TestImages.jpeg(100, 100), "image/jpeg")).header("Authorization", alice))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(body).get("url").asText();

        mvc.perform(multipart("/api/events/" + eventId + "/photos")
                        .file(file(TestImages.jpeg(100, 100), "image/jpeg")).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/events/" + eventId + "/photos").header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(get(url).header("Authorization", bob)).andExpect(status().isNotFound());
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void fileThatIsNotAnImageIsRejectedAndNothingIsStored() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        // Type déclaré mensonger : le contenu fait foi.
        mvc.perform(multipart("/api/events/" + eventId + "/photos")
                        .file(file("#!/bin/sh\necho".getBytes(), "image/jpeg")).header("Authorization", alice))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        mvc.perform(multipart("/api/events/" + eventId + "/photos").header("Authorization", alice))
                .andExpect(status().isBadRequest());
        assertThat(photos.count()).isZero();
        assertThat(mongo.getCollection("fs.files").countDocuments()).isZero();
    }

    /**
     * Critère « 5 Mo en moins de 10 s » : la part serveur (réception, contrôle, miniature, GridFS) d'une photo
     * de 5 Mo doit rester très en deçà, pour laisser l'essentiel du budget au transfert 4G.
     */
    @Test
    void fiveMegabytePhotoIsProcessedWellUnderTheTenSecondBudget() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        byte[] png = TestImages.noisePng(1300, 1300);
        assertThat(png.length).isGreaterThanOrEqualTo(5_000_000);

        Instant start = Instant.now();
        mvc.perform(multipart("/api/events/" + eventId + "/photos").file(file(png, "image/png"))
                        .header("Authorization", alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.size").value(png.length));
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void deletingTheEventDeletesItsPhotosAndFilesOnly() throws Exception {
        String alice = tokenFor("alice@example.com");
        String deleted = createEvent(alice);
        String kept = createEvent(alice);
        for (String eventId : new String[] {deleted, deleted, kept}) {
            mvc.perform(multipart("/api/events/" + eventId + "/photos").file(file(TestImages.jpeg(64, 48), "image/jpeg"))
                            .header("Authorization", alice))
                    .andExpect(status().isCreated());
        }
        assertThat(mongo.getCollection("fs.files").countDocuments()).isEqualTo(6);

        mvc.perform(delete("/api/events/" + deleted).header("Authorization", alice)).andExpect(status().isNoContent());

        assertThat(photos.findAll()).singleElement().satisfies(p -> assertThat(p.getEventId()).isEqualTo(kept));
        assertThat(mongo.getCollection("fs.files").countDocuments()).isEqualTo(2);
        assertThat(mongo.getCollection("fs.files").countDocuments(new Document("metadata.eventId", deleted))).isZero();
        mvc.perform(get("/api/events/" + kept + "/photos").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private static MockMultipartFile part(String name, byte[] data, String type) {
        return new MockMultipartFile("files", name, type, data);
    }

    @Test
    void batchOfTenPhotosIsStoredInOneRequestAndRejectedFilesAreReported() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        var request = multipart("/api/events/" + eventId + "/photos/batch");
        for (int i = 0; i < 8; i++) {
            request.file(part("photo" + i + ".jpg", TestImages.jpeg(100 + i, 50), "image/jpeg"));
        }
        request.file(part("dessin.png", TestImages.png(30, 30), "image/png"));
        request.file(part("notes.pdf", "%PDF-1.7".getBytes(), "image/jpeg"));

        mvc.perform(request.header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(9))
                .andExpect(jsonPath("$.photos[0].width").value(100))
                .andExpect(jsonPath("$.photos[8].contentType").value("image/png"))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].index").value(9))
                .andExpect(jsonPath("$.rejected[0].filename").value("notes.pdf"))
                .andExpect(jsonPath("$.rejected[0].status").value(415));

        assertThat(photos.count()).isEqualTo(9);
        assertThat(mongo.getCollection("fs.files").countDocuments()).isEqualTo(18);
        mvc.perform(get("/api/events/" + eventId + "/photos").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void batchOfElevenPhotosOrForAnotherUsersEventIsRefusedAndNothingIsStored() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String eventId = createEvent(alice);
        var tooMany = multipart("/api/events/" + eventId + "/photos/batch");
        for (int i = 0; i < 11; i++) {
            tooMany.file(part("p" + i + ".jpg", TestImages.jpeg(10, 10), "image/jpeg"));
        }
        mvc.perform(tooMany.header("Authorization", alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trop de photos : 10 au maximum par envoi"));
        mvc.perform(multipart("/api/events/" + eventId + "/photos/batch")
                        .file(part("a.jpg", TestImages.jpeg(10, 10), "image/jpeg")).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/events/" + eventId + "/photos/batch").header("Authorization", alice))
                .andExpect(status().isBadRequest());
        assertThat(photos.count()).isZero();
        assertThat(mongo.getCollection("fs.files").countDocuments()).isZero();
    }

    /** Critère « 15 Mo maximum » : un fichier de 12 Mo passe, un fichier de plus de 15 Mo est refusé (413). */
    @Test
    void photoOfTwelveMegabytesIsAcceptedAndOneOverFifteenMegabytesIsRefused() throws Exception {
        String alice = tokenFor("alice@example.com");
        String eventId = createEvent(alice);
        byte[] png = TestImages.noisePng(2000, 2000);
        assertThat(png.length).isBetween(11_000_000, 15 * 1024 * 1024);
        mvc.perform(multipart("/api/events/" + eventId + "/photos").file(file(png, "image/png"))
                        .header("Authorization", alice))
                .andExpect(status().isCreated());

        byte[] big = new byte[15 * 1024 * 1024 + 1];
        System.arraycopy(TestImages.jpeg(10, 10), 0, big, 0, 3);
        mvc.perform(multipart("/api/events/" + eventId + "/photos/batch")
                        .file(part("enorme.jpg", big, "image/jpeg")).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(0))
                .andExpect(jsonPath("$.rejected[0].status").value(413))
                .andExpect(jsonPath("$.rejected[0].message").value("Photo trop volumineuse (maximum 15 Mo)"));
        assertThat(photos.count()).isEqualTo(1);
    }
}
