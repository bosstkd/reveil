package com.reveil.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.util.ArrayList;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Connexion multi-appareils, rafraîchissement et déconnexion de bout en bout (MongoDB embarqué). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        sessions.deleteAll();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("eve@example.com", "secret123")))
                .andExpect(status().isCreated());
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void sameAccountOnWebAndMobileGivesAccessToTheSameUserData() throws Exception {
        JsonNode web = login("eve@example.com", "secret123");
        JsonNode mobile = login(" EVE@example.com ", "secret123");

        assertThat(web.get("token").asText()).isNotBlank();
        assertThat(web.get("refreshToken").asText()).isNotEqualTo(mobile.get("refreshToken").asText());
        assertThat(web.at("/user/id").asText()).isEqualTo(mobile.at("/user/id").asText());

        for (JsonNode device : new JsonNode[] {web, mobile}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + device.get("token").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(web.at("/user/id").asText()))
                    .andExpect(jsonPath("$.email").value("eve@example.com"));
        }
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialsGiveTheSameGenericErrorWhateverTheField() throws Exception {
        String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("eve@example.com", "mauvais123")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("personne@example.com", "secret123")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String empty = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("", "")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail).isEqualTo(empty)
                .contains("E-mail ou mot de passe incorrect")
                .doesNotContain("field");
        assertThat(sessions.count()).isEqualTo(1); // seule la session ouverte par l'inscription
    }

    @Test
    void refreshRotatesTokensAndKeepsTheSession() throws Exception {
        JsonNode mobile = login("eve@example.com", "secret123");
        JsonNode renewed = refresh(mobile.get("refreshToken").asText(), 200);

        assertThat(renewed.get("refreshToken").asText()).isNotEqualTo(mobile.get("refreshToken").asText());
        assertThat(renewed.at("/user/email").asText()).isEqualTo("eve@example.com");
        assertThat(me(renewed.get("token").asText())).isEqualTo(200);

        refresh("inconnu.secret", 401);
    }

    @Test
    void logoutInvalidatesOnlyTheCurrentDevice() throws Exception {
        JsonNode web = login("eve@example.com", "secret123");
        JsonNode mobile = login("eve@example.com", "secret123");

        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mobile.get("token").asText())
                        .content("{\"refreshToken\":\"" + mobile.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(me(mobile.get("token").asText())).isEqualTo(401); // immédiat, sans attendre l'expiration du JWT
        refresh(mobile.get("refreshToken").asText(), 401);
        assertThat(me(web.get("token").asText())).isEqualTo(200);
        refresh(web.get("refreshToken").asText(), 200);
    }

    @Test
    void sessionsAreStoredHashedWithTtlIndex() throws Exception {
        JsonNode web = login("eve@example.com", "secret123");
        String refreshToken = web.get("refreshToken").asText();
        String sessionId = refreshToken.substring(0, refreshToken.indexOf('.'));

        Document raw = mongo.getCollection("sessions").find(new Document("_id", sessionId)).first();
        assertThat(raw).isNotNull();
        assertThat(raw.toJson()).doesNotContain(refreshToken.substring(refreshToken.indexOf('.') + 1));
        assertThat(raw.getString("userId")).isEqualTo(web.at("/user/id").asText());

        boolean ttl = mongo.getCollection("sessions").listIndexes().into(new ArrayList<>()).stream()
                .anyMatch(i -> new Document("expiresAt", 1).equals(i.get("key")) && i.get("expireAfterSeconds") != null);
        assertThat(ttl).isTrue();
    }
}
