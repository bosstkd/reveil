package com.reveil.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.device.DeviceTokenRepository;
import com.reveil.event.EventRepository;
import com.reveil.photo.PhotoRepository;
import com.reveil.photo.TestImages;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
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

/** Suppression du compte de bout en bout : confirmation, cascade, isolement des autres comptes (MongoDB embarqué). */
@SpringBootTest(properties = "reveil.accounts.purge-grace=PT0S")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountIntegrationTest {

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
    DeviceTokenRepository devices;
    @Autowired
    AccountDeletionRepository deletions;
    @Autowired
    AccountService accounts;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;

    record Account(String id, String bearer, String refreshToken) {
    }

    @BeforeEach
    void clean() {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        photos.deleteAll();
        devices.deleteAll();
        deletions.deleteAll();
        mongo.getCollection("password_reset_tokens").deleteMany(new Document());
        mongo.getCollection("web_push_subscriptions").deleteMany(new Document());
        mongo.getCollection("fs.files").deleteMany(new Document());
        mongo.getCollection("fs.chunks").deleteMany(new Document());
    }

    private Account register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new Account(node.at("/user/id").asText(), "Bearer " + node.get("token").asText(),
                node.get("refreshToken").asText());
    }

    /** Événement avec rappels, une photo (original + miniature GridFS), un appareil et un navigateur abonné. */
    private String populate(Account account, String deviceToken) throws Exception {
        String body = mvc.perform(post("/api/events").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"2099-12-24\",\"time\":\"20:30\","
                                + "\"timeZone\":\"UTC\",\"reminders\":[60,1440]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String eventId = json.readTree(body).get("id").asText();
        mvc.perform(multipart("/api/events/" + eventId + "/photos")
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", TestImages.jpeg(800, 600)))
                        .header("Authorization", account.bearer()))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/devices").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + deviceToken + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(put("/api/push/web/subscription").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(com.reveil.webpush.WebPushTestData.subscription(
                                "https://fcm.googleapis.com/fcm/send/" + deviceToken))))
                .andExpect(status().isNoContent());
        return eventId;
    }

    private long gridFsFilesOf(String ownerId) {
        return mongo.getCollection("fs.files").countDocuments(new Document("metadata.ownerId", ownerId));
    }

    private void deleteAccount(Account account, String password, int expectedStatus) throws Exception {
        mvc.perform(delete("/api/account").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("password", password))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void mauvaisMotDePasseRefuseEn403SansRienSupprimer() throws Exception {
        Account alice = register("alice@example.com");
        String eventId = populate(alice, "fcm-alice");

        mvc.perform(delete("/api/account").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"mauvais1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Mot de passe incorrect"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        assertThat(users.findById(alice.id())).isPresent();
        assertThat(events.findById(eventId)).isPresent();
        assertThat(gridFsFilesOf(alice.id())).isEqualTo(2);
        assertThat(deletions.count()).isZero();
        mvc.perform(get("/api/auth/me").header("Authorization", alice.bearer())).andExpect(status().isOk());
    }

    @Test
    void motDePasseAbsentOuSansJeton() throws Exception {
        Account alice = register("alice@example.com");
        mvc.perform(delete("/api/account").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        mvc.perform(delete("/api/account").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(alice.id())).isPresent();
    }

    @Test
    void suppressionEnCascadeDesDonneesDuCompteSeulement() throws Exception {
        Account alice = register("alice@example.com");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()); // 2e appareil : 2e session
        Account bob = register("bob@example.com");
        // Lien de réinitialisation du mot de passe en cours (story 13).
        mongo.getCollection("password_reset_tokens").insertOne(new Document("_id", "hash-alice")
                .append("userId", alice.id()).append("expiresAt", new java.util.Date()));
        String aliceEvent = populate(alice, "fcm-alice");
        String bobEvent = populate(bob, "fcm-bob");

        deleteAccount(alice, "secret123", 204);

        // Compte, sessions (tous appareils), événements et rappels, photos, fichiers, jetons d'appareils : supprimés.
        assertThat(users.findById(alice.id())).isEmpty();
        assertThat(mongo.getCollection("sessions").countDocuments(new Document("userId", alice.id()))).isZero();
        assertThat(events.findById(aliceEvent)).isEmpty();
        assertThat(mongo.getCollection("photos").countDocuments(new Document("ownerId", alice.id()))).isZero();
        assertThat(gridFsFilesOf(alice.id())).isZero();
        assertThat(devices.findByUserId(alice.id())).isEmpty();
        assertThat(devices.findByToken("fcm-alice")).isEmpty();
        assertThat(webPushOf(alice.id())).isZero();
        assertThat(mongo.getCollection("password_reset_tokens").countDocuments(new Document("userId", alice.id())))
                .isZero();
        assertThat(deletions.findById(alice.id())).isPresent();

        // Jetons refusés immédiatement, connexion impossible, l'e-mail redevient libre.
        mvc.perform(get("/api/auth/me").header("Authorization", alice.bearer())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + alice.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());

        // Les données de Bob sont intactes.
        assertThat(users.findById(bob.id())).isPresent();
        assertThat(events.findById(bobEvent)).isPresent();
        assertThat(events.findById(bobEvent).orElseThrow().getReminders()).hasSize(2);
        assertThat(mongo.getCollection("photos").countDocuments(new Document("ownerId", bob.id()))).isEqualTo(1);
        assertThat(gridFsFilesOf(bob.id())).isEqualTo(2);
        assertThat(devices.findByToken("fcm-bob")).isPresent();
        assertThat(webPushOf(bob.id())).isEqualTo(1);
        mvc.perform(get("/api/auth/me").header("Authorization", bob.bearer())).andExpect(status().isOk());
    }

    private long webPushOf(String userId) {
        return mongo.getCollection("web_push_subscriptions").countDocuments(new Document("userId", userId));
    }

    @Test
    void passeDeControleSupprimeLesEcrituresEnVolPuisCloLaDemande() throws Exception {
        Account alice = register("alice@example.com");
        Account bob = register("bob@example.com");
        populate(bob, "fcm-bob");
        deleteAccount(alice, "secret123", 204);

        // Écritures arrivées après la purge immédiate (requête déjà authentifiée au moment de la suppression).
        mongo.getCollection("events").insertOne(new Document("title", "Tardif").append("ownerId", alice.id()));
        mongo.getCollection("photos").insertOne(new Document("eventId", "x").append("ownerId", alice.id()));
        mongo.getCollection("device_tokens").insertOne(new Document("token", "fcm-late").append("userId", alice.id()));
        mongo.getCollection("password_reset_tokens").insertOne(new Document("_id", "late").append("userId", alice.id()));
        mongo.getCollection("web_push_subscriptions").insertOne(new Document("endpoint", "https://fcm.googleapis.com/late")
                .append("userId", alice.id()));

        assertThat(accounts.purgePending()).isEqualTo(1);

        assertThat(mongo.getCollection("events").countDocuments(new Document("ownerId", alice.id()))).isZero();
        assertThat(mongo.getCollection("photos").countDocuments(new Document("ownerId", alice.id()))).isZero();
        assertThat(mongo.getCollection("device_tokens").countDocuments(new Document("userId", alice.id()))).isZero();
        assertThat(mongo.getCollection("password_reset_tokens").countDocuments(new Document("userId", alice.id())))
                .isZero();
        assertThat(webPushOf(alice.id())).isZero();
        assertThat(deletions.count()).isZero();
        assertThat(mongo.getCollection("events").countDocuments(new Document("ownerId", bob.id()))).isEqualTo(1);
        assertThat(accounts.purgePending()).isZero();
    }
}
