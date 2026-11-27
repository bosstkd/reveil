package com.reveil.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.event.EventRepository;
import com.reveil.mail.EmailMessage;
import com.reveil.mail.EmailSender;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Mot de passe oublié de bout en bout : demande, e-mail, lien à usage unique et durée limitée (MongoDB embarqué). */
@SpringBootTest(properties = "reveil.password-reset.web-url=https://reveil.example.com/test/reveil/")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    private static final Pattern WEB_LINK =
            Pattern.compile("https://reveil\\.example\\.com/test/reveil/reinitialiser-mot-de-passe\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    EventRepository events;
    @Autowired
    PasswordResetTokenRepository tokens;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    EmailSender emailSender;

    private JsonNode alice;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        tokens.deleteAll();
        reset(emailSender);
        alice = json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alice@example.com", "ancien123")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String requestReset(String email) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    /** Demande un lien pour Alice et renvoie le jeton extrait de l'e-mail envoyé. */
    private String tokenFromEmail() throws Exception {
        requestReset("alice@example.com");
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, timeout(5000)).send(sent.capture());
        reset(emailSender);
        Matcher m = WEB_LINK.matcher(sent.getValue().body());
        assertThat(m.find()).as("lien web dans l'e-mail").isTrue();
        assertThat(sent.getValue().body()).contains("reveil://reinitialiser-mot-de-passe?token=" + m.group(1));
        return m.group(1);
    }

    private int confirm(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private int login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alice@example.com", password)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void memeReponseQueLeCompteExisteOuNon() throws Exception {
        String existing = requestReset("alice@example.com");
        String unknown = requestReset("personne@example.com");
        String other = requestReset("ALICE@Example.com");

        assertThat(existing).isEqualTo(unknown).isEqualTo(other).contains(PasswordResetService.CONFIRMATION_MESSAGE);
        verify(emailSender, timeout(5000).times(2)).send(any());
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(m -> {
            assertThat(m.to()).isEqualTo("alice@example.com");
            assertThat(m.subject()).isEqualTo(PasswordResetService.SUBJECT);
            assertThat(m.body()).contains("1 heure");
        });
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void emailInvalideRefuseSansRienEnvoyer() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pas-un-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        verify(emailSender, after(300).never()).send(any());
    }

    @Test
    void seulLeHashDuJetonEstStocke() throws Exception {
        String token = tokenFromEmail();
        PasswordResetToken stored = tokens.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(PasswordResetService.hash(token)).isNotEqualTo(token);
        assertThat(stored.getUserId()).isEqualTo(alice.at("/user/id").asText());
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plusSeconds(3600));
        assertThat(mongo.getCollection("password_reset_tokens").find().first().toJson()).doesNotContain(token);
    }

    @Test
    void apresReinitialisationNouveauMotDePasseAccepteAncienRefuseDonneesConservees() throws Exception {
        String bearer = "Bearer " + alice.get("token").asText();
        mvc.perform(post("/api/events").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"2099-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isCreated());

        assertThat(confirm(tokenFromEmail(), "nouveau456")).isEqualTo(204);

        assertThat(login("ancien123")).isEqualTo(401);
        assertThat(login("nouveau456")).isEqualTo(200);
        // Les sessions ouvertes avec l'ancien mot de passe sont révoquées.
        mvc.perform(get("/api/auth/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + alice.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
        // Même compte : événements (et donc photos) conservés.
        assertThat(users.findByEmail("alice@example.com").orElseThrow().getId()).isEqualTo(alice.at("/user/id").asText());
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void lienUtilisableUneSeuleFois() throws Exception {
        String token = tokenFromEmail();
        assertThat(confirm(token, "nouveau456")).isEqualTo(204);

        mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", "pirate789"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(InvalidResetTokenException.MESSAGE))
                .andExpect(jsonPath("$.errors[0].field").value("token"));
        assertThat(login("nouveau456")).isEqualTo(200);
        assertThat(login("pirate789")).isEqualTo(401);
    }

    @Test
    void reinitialisationInvalideLesAutresLiensDuCompte() throws Exception {
        String first = tokenFromEmail();
        String second = tokenFromEmail();
        assertThat(confirm(second, "nouveau456")).isEqualTo(204);
        assertThat(confirm(first, "autre7890")).isEqualTo(400);
        assertThat(tokens.count()).isZero();
    }

    @Test
    void lienExpireApresUneHeure() throws Exception {
        String token = tokenFromEmail();
        // Une heure s'est écoulée (l'index TTL ne supprime le document qu'avec retard : l'expiration est vérifiée).
        mongo.getCollection("password_reset_tokens").updateMany(new Document(),
                new Document("$set", new Document("expiresAt", java.util.Date.from(Instant.now().minusSeconds(1)))));

        assertThat(confirm(token, "nouveau456")).isEqualTo(400);
        assertThat(login("ancien123")).isEqualTo(200);
        assertThat(login("nouveau456")).isEqualTo(401);
    }

    @Test
    void jetonInconnuOuAbsentRefuse() throws Exception {
        assertThat(confirm("inconnu", "nouveau456")).isEqualTo(400);
        mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"nouveau456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"));
    }

    @Test
    void motDePasseNonConformeRefuseSansConsommerLeLien() throws Exception {
        String token = tokenFromEmail();
        mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", "court"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        assertThat(confirm(token, "nouveau456")).isEqualTo(204);
    }

    @Test
    void nombreDeLiensActifsLimite() throws Exception {
        for (int i = 0; i < 5; i++) {
            requestReset("alice@example.com");
        }
        verify(emailSender, timeout(5000).times(3)).send(any());
        verify(emailSender, after(300).times(3)).send(any());
        assertThat(tokens.count()).isEqualTo(3);
    }

    @Test
    void routesPubliquesSansJetonDAcces() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordResetService.CONFIRMATION_MESSAGE));
        verify(emailSender, timeout(5000)).send(any());
        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.argThat(m -> !m.to().equals("alice@example.com")));
    }
}
