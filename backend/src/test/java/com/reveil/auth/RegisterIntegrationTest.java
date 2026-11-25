package com.reveil.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.user.User;
import com.reveil.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Inscription de bout en bout sur une base MongoDB embarquée (flapdoodle). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegisterIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    TokenService tokens;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void registersUserStoresBcryptHashAndReturnsSessionTokenQuickly() throws Exception {
        long start = System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bob@Example.com", "secret123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("bob@example.com"))
                .andReturn().getResponse().getContentAsString();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(3000);

        Document raw = mongo.getCollection("users").find(new Document("email", "bob@example.com")).first();
        assertThat(raw).isNotNull();
        assertThat(raw.toJson()).doesNotContain("secret123");
        String hash = raw.getString("passwordHash");
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("secret123", hash)).isTrue();
        assertThat(raw.get("createdAt")).isNotNull();

        String token = response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(tokens.parseSubject(token)).isEqualTo(raw.getObjectId("_id").toHexString());
    }

    @Test
    void secondRegistrationWithSameEmailIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("carol@example.com", "secret123")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("CAROL@example.com", "autre1234")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Un compte existe déjà avec cet e-mail"));

        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void uniqueIndexOnEmailIsEnforcedByMongo() {
        boolean hasUniqueIndex = mongo.getCollection("users").listIndexes().into(new java.util.ArrayList<>())
                .stream().anyMatch(i -> new Document("email", 1).equals(i.get("key")) && Boolean.TRUE.equals(i.get("unique")));
        assertThat(hasUniqueIndex).isTrue();

        users.save(new User("dave@example.com", encoder.encode("secret123")));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> users.save(new User("dave@example.com", encoder.encode("secret456"))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void invalidPayloadIsNotPersisted() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("pas-un-email", "court")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        assertThat(users.count()).isZero();
    }
}
