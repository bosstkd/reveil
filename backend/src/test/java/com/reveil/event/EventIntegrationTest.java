package com.reveil.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.util.Date;
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

/** Création d'événements de bout en bout (MongoDB embarqué). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    EventRepository events;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clean() {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
    }

    private String tokenFor(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("token").asText();
    }

    @Test
    void createdEventIsStoredInUtcWithTimeZoneAndOwnerThenListed() throws Exception {
        String alice = tokenFor("alice@example.com");

        mvc.perform(post("/api/events").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"2099-12-24\",\"time\":\"20:30\","
                                + "\"timeZone\":\"Europe/Paris\",\"location\":\"Olympia\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsAt").value("2099-12-24T19:30:00Z"))
                .andExpect(jsonPath("$.past").value(false));

        Document doc = mongo.getCollection("events").find().first();
        assertThat(doc).isNotNull();
        assertThat(doc.get("startsAt")).isInstanceOf(Date.class); // BSON Date (UTC)
        assertThat(doc.getDate("startsAt").toInstant()).hasToString("2099-12-24T19:30:00Z");
        assertThat(doc.getString("timeZone")).isEqualTo("Europe/Paris");
        assertThat(doc.getString("location")).isEqualTo("Olympia");
        assertThat(doc.getString("ownerId")).isEqualTo(users.findByEmail("alice@example.com").orElseThrow().getId());

        mvc.perform(get("/api/events").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Concert"))
                .andExpect(jsonPath("$[0].localDateTime").value("2099-12-24T20:30:00"));
    }

    @Test
    void pastEventIsAcceptedWithWarning() throws Exception {
        String alice = tokenFor("alice@example.com");
        mvc.perform(post("/api/events").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hier\",\"date\":\"2001-01-01\",\"time\":\"08:00\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.past").value(true))
                .andExpect(jsonPath("$.warnings[0]").value(EventResponse.PAST_WARNING));
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void missingTitleIsRejectedAndNothingIsStored() throws Exception {
        String alice = tokenFor("alice@example.com");
        mvc.perform(post("/api/events").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2099-01-01\",\"time\":\"08:00\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        assertThat(events.count()).isZero();
    }

    @Test
    void usersOnlySeeTheirOwnEvents() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        mvc.perform(post("/api/events").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Privé\",\"date\":\"2099-01-01\",\"time\":\"08:00\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/events").header("Authorization", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
