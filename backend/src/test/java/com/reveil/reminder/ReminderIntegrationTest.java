package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.device.DeviceTokenRepository;
import com.reveil.event.Event;
import com.reveil.event.EventRepository;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Rappels de bout en bout : appareil enregistré, événement créé, planificateur, envoi (MongoDB embarqué). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReminderIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    EventRepository events;
    @Autowired
    DeviceTokenRepository devices;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ObjectMapper json;
    @Autowired
    ReminderService reminders;
    @MockitoBean
    PushSender push;
    @Value("${reveil.reminders.cron}")
    String cron;

    @BeforeEach
    void clean() {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        devices.deleteAll();
        reset(push);
        when(push.send(any())).thenReturn(PushResult.SENT);
    }

    private JsonNode register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static String bearer(JsonNode auth) {
        return "Bearer " + auth.get("token").asText();
    }

    private void registerDevice(String bearer, String token) throws Exception {
        mvc.perform(put("/api/devices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"platform\":\"android\"}"))
                .andExpect(status().isNoContent());
    }

    /** Crée un événement commençant dans {@code in} (heure UTC, à la minute). Renvoie son id. */
    private String createEvent(String bearer, String title, Duration in) throws Exception {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.now().plus(in), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        String body = mvc.perform(post("/api/events").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"date\":\"" + at.toLocalDate() + "\",\"time\":\""
                                + at.toLocalTime() + "\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void reminderIsStoredOneHourBeforeAndSentOnceToTheOwnerDevicesWhenDue() throws Exception {
        JsonNode alice = register("alice@example.com");
        JsonNode bob = register("bob@example.com");
        registerDevice(bearer(alice), "alice-phone");
        registerDevice(bearer(bob), "bob-phone");

        String soon = createEvent(bearer(alice), "Dans 30 min", Duration.ofMinutes(30)); // échéance déjà passée
        String later = createEvent(bearer(alice), "Dans 3 h", Duration.ofHours(3));

        Event laterEvent = events.findById(later).orElseThrow();
        assertThat(laterEvent.getReminderDueAt()).isEqualTo(laterEvent.getStartsAt().minus(Duration.ofHours(1)));

        assertThat(reminders.sendDueReminders()).isEqualTo(1);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).send(sent.capture());
        assertThat(sent.getValue().token()).isEqualTo("alice-phone");
        assertThat(sent.getValue().title()).isEqualTo("Rappel : Dans 30 min");
        assertThat(sent.getValue().data()).containsEntry("eventId", soon);

        Document doc = mongo.getCollection("events").find(new Document("_id", new org.bson.types.ObjectId(soon))).first();
        assertThat(doc).isNotNull();
        assertThat(doc.containsKey("reminderDueAt")).isFalse();
        assertThat(doc.get("reminderSentAt")).isInstanceOf(Date.class);

        // Pas de second envoi au passage suivant.
        assertThat(reminders.sendDueReminders()).isZero();
        verify(push, times(1)).send(any());

        // Le détail de l'événement (ouvert depuis la notification) est accessible au propriétaire seulement.
        mvc.perform(get("/api/events/" + soon).header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Dans 30 min"));
        mvc.perform(get("/api/events/" + soon).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void noReminderIsSentForADeletedEvent() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Annulé", Duration.ofMinutes(30));

        mvc.perform(delete("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent()); // le rappel disparaît avec le document

        assertThat(reminders.sendDueReminders()).isZero();
        verify(push, never()).send(any());
        mvc.perform(get("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());
    }

    /** Déplace l'événement {@code id} pour qu'il commence dans {@code in} (heure UTC, à la minute). */
    private void moveEvent(String bearer, String id, String title, Duration in) throws Exception {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.now().plus(in), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        mvc.perform(put("/api/events/" + id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"date\":\"" + at.toLocalDate() + "\",\"time\":\""
                                + at.toLocalTime() + "\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changingTheDateRecomputesTheReminderOnTheNewDate() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Concert", Duration.ofMinutes(30)); // rappel déjà échu

        moveEvent(bearer(alice), id, "Concert", Duration.ofDays(2));

        Event moved = events.findById(id).orElseThrow();
        assertThat(moved.getReminderDueAt()).isEqualTo(moved.getStartsAt().minus(Duration.ofHours(1)));
        assertThat(reminders.sendDueReminders()).isZero(); // l'ancienne échéance ne part plus
        verify(push, never()).send(any());

        // Ramené dans la demi-heure : nouveau rappel immédiatement dû, envoyé une fois.
        moveEvent(bearer(alice), id, "Concert avancé", Duration.ofMinutes(20));
        assertThat(reminders.sendDueReminders()).isEqualTo(1);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).send(sent.capture());
        assertThat(sent.getValue().title()).isEqualTo("Rappel : Concert avancé");
    }

    @Test
    void alreadySentReminderIsRescheduledWhenTheDateChangesButNotOnOtherChanges() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Concert", Duration.ofMinutes(30));
        assertThat(reminders.sendDueReminders()).isEqualTo(1);

        // Titre modifié, même date : pas de second rappel.
        Event sent = events.findById(id).orElseThrow();
        LocalDateTime same = LocalDateTime.ofInstant(sent.getStartsAt(), ZoneOffset.UTC);
        mvc.perform(put("/api/events/" + id).header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert (salle B)\",\"date\":\"" + same.toLocalDate() + "\",\"time\":\""
                                + same.toLocalTime() + "\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isOk());
        assertThat(events.findById(id).orElseThrow().getReminderDueAt()).isNull();
        assertThat(reminders.sendDueReminders()).isZero();

        // Date reportée : un nouveau rappel est programmé.
        moveEvent(bearer(alice), id, "Concert", Duration.ofDays(1));
        Event moved = events.findById(id).orElseThrow();
        assertThat(moved.getReminderDueAt()).isEqualTo(moved.getStartsAt().minus(Duration.ofHours(1)));
        assertThat(moved.getReminderSentAt()).isNull();

        // Déplacé dans le passé : plus aucun rappel.
        moveEvent(bearer(alice), id, "Concert", Duration.ofDays(-1));
        assertThat(events.findById(id).orElseThrow().getReminderDueAt()).isNull();
        verify(push, times(1)).send(any());
    }

    @Test
    void invalidTokensAreRemovedAndLoggedOutDevicesReceiveNothing() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "uninstalled");
        JsonNode otherDevice = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret123\"}"))
                .andReturn().getResponse().getContentAsString());
        registerDevice(bearer(otherDevice), "tablet");
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + otherDevice.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());
        when(push.send(any())).thenReturn(PushResult.INVALID_TOKEN);

        createEvent(bearer(alice), "Concert", Duration.ofMinutes(30));
        reminders.sendDueReminders();

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).send(sent.capture());
        assertThat(sent.getValue().token()).isEqualTo("uninstalled");
        assertThat(devices.count()).isZero(); // invalide : supprimé ; session fermée : supprimé
    }

    @Test
    void sameDeviceTokenMovesToTheLastUserWhoLoggedIn() throws Exception {
        registerDevice(bearer(register("alice@example.com")), "shared-phone");
        JsonNode bob = register("bob@example.com");
        registerDevice(bearer(bob), "shared-phone");

        assertThat(devices.count()).isEqualTo(1);
        assertThat(devices.findByToken("shared-phone").orElseThrow().getUserId())
                .isEqualTo(users.findByEmail("bob@example.com").orElseThrow().getId());
    }

    @Test
    void pastEventsGetNoReminderAndExistingUpcomingEventsAreScheduled() throws Exception {
        JsonNode alice = register("alice@example.com");
        createEvent(bearer(alice), "Hier", Duration.ofDays(-1));
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getReminderDueAt()).isNull());

        // Événement créé avant cette fonctionnalité : aucun champ de rappel.
        Instant startsAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        mongo.getCollection("events").insertOne(new Document("title", "Ancien").append("startsAt", Date.from(startsAt))
                .append("timeZone", "UTC").append("ownerId", "u1"));

        assertThat(reminders.scheduleMissingReminders()).isEqualTo(1);
        Document doc = mongo.getCollection("events").find(new Document("title", "Ancien")).first();
        assertThat(doc.getDate("reminderDueAt").toInstant()).isEqualTo(startsAt.minus(Duration.ofHours(1)));
        assertThat(reminders.scheduleMissingReminders()).isZero(); // idempotent
    }

    @Test
    void dueRemindersAreFoundThroughTheirIndex() {
        assertThat(mongo.indexOps(Event.class).getIndexInfo())
                .anySatisfy(ix -> assertThat(ix.getName()).isEqualTo("ix_events_reminder_due_at"));
        Document plan = mongo.getCollection("events")
                .find(new Document("reminderDueAt", new Document("$lte", new Date()))).explain();
        assertThat(plan.toJson()).contains("ix_events_reminder_due_at");
    }

    @Test
    void schedulerRunsAtLeastEveryMinute() {
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime t = LocalDateTime.of(2026, 11, 25, 18, 29, 0, 1_000_000);
        LocalDateTime next = expression.next(t);
        assertThat(Duration.between(t, next)).isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(Duration.between(next, expression.next(next))).isLessThanOrEqualTo(Duration.ofMinutes(1));
        // Échéance à la minute pile : traitée au passage de la seconde 0 de cette minute.
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 11, 25, 18, 30, 0));
    }
}
