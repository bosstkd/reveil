package com.reveil.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Concert"))
                .andExpect(jsonPath("$.items[0].localDateTime").value("2099-12-24T20:30:00"));
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
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/events").param("when", "past").header("Authorization", bob))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    private String ownerId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    @Test
    void upcomingAndPastAreSeparatedAndSorted() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Instant now = Instant.now();
        String a = ownerId("alice@example.com");
        events.saveAll(List.of(
                new Event("Dans 3 jours", now.plus(3, ChronoUnit.DAYS), "UTC", null, a),
                new Event("Demain", now.plus(1, ChronoUnit.DAYS), "UTC", null, a),
                new Event("Dans 2 jours", now.plus(2, ChronoUnit.DAYS), "UTC", null, a),
                new Event("Avant-hier", now.minus(2, ChronoUnit.DAYS), "UTC", null, a),
                new Event("Hier", now.minus(1, ChronoUnit.DAYS), "UTC", null, a),
                new Event("Chez Bob", now.plus(1, ChronoUnit.HOURS), "UTC", null, ownerId("bob@example.com"))));

        mvc.perform(get("/api/events").param("when", "upcoming").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title").value(contains("Demain", "Dans 2 jours", "Dans 3 jours")))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/events").param("when", "past").header("Authorization", alice))
                .andExpect(jsonPath("$.items[*].title").value(contains("Hier", "Avant-hier")))
                .andExpect(jsonPath("$.items[*].past").value(everyItem(is(true))));
        mvc.perform(get("/api/events").header("Authorization", bob))
                .andExpect(jsonPath("$.items[*].title").value(contains("Chez Bob")));

        // Pagination : 2 par page.
        mvc.perform(get("/api/events").param("size", "2").param("page", "1").header("Authorization", alice))
                .andExpect(jsonPath("$.items[*].title").value(contains("Dans 3 jours")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void hundredEventsAreListedWellUnderTwoSecondsUsingTheOwnerDateIndex() throws Exception {
        String alice = tokenFor("alice@example.com");
        String a = ownerId("alice@example.com");
        Instant now = Instant.now();
        List<Event> many = new ArrayList<>();
        for (int i = 100; i >= 1; i--) {
            many.add(new Event("Événement " + i, now.plus(i, ChronoUnit.HOURS), "Europe/Paris", null, a));
        }
        events.saveAll(many);
        mvc.perform(get("/api/events").param("size", "100").header("Authorization", alice)); // préchauffage

        long start = System.nanoTime();
        mvc.perform(get("/api/events").param("size", "100").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.items[0].title").value("Événement 1"))
                .andExpect(jsonPath("$.items[99].title").value("Événement 100"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));

        assertThat(mongo.indexOps(Event.class).getIndexInfo())
                .anySatisfy(ix -> assertThat(ix.getName()).isEqualTo("ix_events_owner_starts_at"));
        Document plan = mongo.getCollection("events")
                .find(new Document("ownerId", a).append("startsAt", new Document("$gte", Date.from(now))))
                .sort(new Document("startsAt", 1))
                .explain();
        assertThat(plan.toJson()).contains("IXSCAN").contains("ix_events_owner_starts_at").doesNotContain("\"SORT\"");
    }

    private String createConcert(String token) throws Exception {
        String body = mvc.perform(post("/api/events").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"2099-12-24\",\"time\":\"20:30\","
                                + "\"timeZone\":\"Europe/Paris\",\"location\":\"Olympia\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void everyEnteredFieldCanBeUpdatedByTheOwner() throws Exception {
        String alice = tokenFor("alice@example.com");
        String id = createConcert(alice);

        mvc.perform(put("/api/events/" + id).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert reporté\",\"date\":\"2099-12-31\",\"time\":\"22:00\","
                                + "\"timeZone\":\"America/New_York\",\"location\":\"Bercy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Concert reporté"))
                .andExpect(jsonPath("$.startsAt").value("2100-01-01T03:00:00Z"))
                .andExpect(jsonPath("$.localDateTime").value("2099-12-31T22:00:00"))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.location").value("Bercy"));

        // Lieu vidé : retiré du document.
        mvc.perform(put("/api/events/" + id).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert reporté\",\"date\":\"2099-12-31\",\"time\":\"22:00\","
                                + "\"timeZone\":\"America/New_York\",\"location\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").doesNotExist());

        mvc.perform(get("/api/events/" + id).header("Authorization", alice))
                .andExpect(jsonPath("$.title").value("Concert reporté"))
                .andExpect(jsonPath("$.location").doesNotExist());
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void deletedEventDisappearsFromTheOwnerList() throws Exception {
        String alice = tokenFor("alice@example.com");
        String id = createConcert(alice);

        mvc.perform(delete("/api/events/" + id).header("Authorization", alice)).andExpect(status().isNoContent());

        assertThat(events.count()).isZero();
        mvc.perform(get("/api/events/" + id).header("Authorization", alice)).andExpect(status().isNotFound());
        mvc.perform(get("/api/events").header("Authorization", alice)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete("/api/events/" + id).header("Authorization", alice)).andExpect(status().isNotFound());
        mvc.perform(put("/api/events/" + id).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"X\",\"date\":\"2099-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCanNeitherUpdateNorDeleteTheEvent() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String id = createConcert(alice);

        mvc.perform(put("/api/events/" + id).header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Piraté\",\"date\":\"2099-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/events/" + id).header("Authorization", bob)).andExpect(status().isForbidden());

        mvc.perform(get("/api/events/" + id).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Concert"))
                .andExpect(jsonPath("$.location").value("Olympia"));
    }

    @Test
    void detailReturnsInformationAndConfiguredRemindersToTheOwnerOnly() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String id = createConcert(alice);

        mvc.perform(get("/api/events/" + id).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Concert"))
                .andExpect(jsonPath("$.startsAt").value("2099-12-24T19:30:00Z"))
                .andExpect(jsonPath("$.localDateTime").value("2099-12-24T20:30:00"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"))
                .andExpect(jsonPath("$.location").value("Olympia"))
                .andExpect(jsonPath("$.past").value(false))
                .andExpect(jsonPath("$.reminders.length()").value(1))
                .andExpect(jsonPath("$.reminders[0].minutesBefore").value(60))
                .andExpect(jsonPath("$.reminders[0].remindAt").value("2099-12-24T18:30:00Z"))
                .andExpect(jsonPath("$.reminders[0].status").value("scheduled"));

        // Un autre utilisateur ne voit pas l'événement (pas de fuite d'existence) ; identifiant inconnu : 404.
        mvc.perform(get("/api/events/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/events/inconnu").header("Authorization", alice)).andExpect(status().isNotFound());
    }
}
