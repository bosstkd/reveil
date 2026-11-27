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
import com.reveil.event.Reminder;
import com.reveil.event.ReminderStatus;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.time.Clock;
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
        return createEvent(bearer, title, in, null);
    }

    /** Idem avec les délais de rappel {@code reminders} (JSON, ex. « [15,60] » ; null : délai par défaut). */
    private String createEvent(String bearer, String title, Duration in, String reminders) throws Exception {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.now().plus(in), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        String body = mvc.perform(post("/api/events").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"date\":\"" + at.toLocalDate() + "\",\"time\":\""
                                + at.toLocalTime() + "\",\"timeZone\":\"UTC\""
                                + (reminders == null ? "" : ",\"reminders\":" + reminders) + "}"))
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
        assertThat(laterEvent.getReminders()).singleElement().satisfies(r ->
                assertThat(r.getDueAt()).isEqualTo(laterEvent.getStartsAt().minus(Duration.ofHours(1))));

        assertThat(reminders.sendDueReminders()).isEqualTo(1);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).send(sent.capture());
        assertThat(sent.getValue().token()).isEqualTo("alice-phone");
        assertThat(sent.getValue().title()).isEqualTo("Rappel : Dans 30 min");
        assertThat(sent.getValue().data()).containsEntry("eventId", soon);

        Document doc = mongo.getCollection("events").find(new Document("_id", new org.bson.types.ObjectId(soon))).first();
        assertThat(doc).isNotNull();
        Document reminder = doc.getList("reminders", Document.class).get(0);
        assertThat(reminder.getString("status")).isEqualTo("SENT");
        assertThat(reminder.get("processedAt")).isInstanceOf(Date.class);

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
        assertThat(moved.getReminders()).singleElement().satisfies(r ->
                assertThat(r.getDueAt()).isEqualTo(moved.getStartsAt().minus(Duration.ofHours(1))));
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
        assertThat(events.findById(id).orElseThrow().getReminders()).extracting(Reminder::getStatus)
                .containsExactly(ReminderStatus.SENT);
        assertThat(reminders.sendDueReminders()).isZero();

        // Date reportée : un nouveau rappel est programmé.
        moveEvent(bearer(alice), id, "Concert", Duration.ofDays(1));
        Event moved = events.findById(id).orElseThrow();
        assertThat(moved.getReminders()).singleElement().satisfies(r -> {
            assertThat(r.getDueAt()).isEqualTo(moved.getStartsAt().minus(Duration.ofHours(1)));
            assertThat(r.getStatus()).isEqualTo(ReminderStatus.SCHEDULED);
            assertThat(r.getProcessedAt()).isNull();
        });

        // Déplacé dans le passé : plus aucun rappel.
        moveEvent(bearer(alice), id, "Concert", Duration.ofDays(-1));
        assertThat(events.findById(id).orElseThrow().getReminders()).isEmpty();
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
    void pastEventsGetNoReminderAndLegacyEventsAreMigrated() throws Exception {
        JsonNode alice = register("alice@example.com");
        createEvent(bearer(alice), "Hier", Duration.ofDays(-1));
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getReminders()).isEmpty());

        Instant startsAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        var raw = mongo.getCollection("events");
        // Créé avant toute gestion des rappels : rappel par défaut.
        raw.insertOne(legacy("Ancien", startsAt));
        // Rappel unique programmé (story 5) : repris avec son délai.
        raw.insertOne(legacy("Programmé", startsAt).append("reminderDueAt", Date.from(startsAt.minus(Duration.ofHours(1)))));
        // Rappel unique déjà envoyé : repris comme envoyé, pas de renvoi.
        raw.insertOne(legacy("Envoyé", startsAt).append("reminderSentAt", Date.from(startsAt.minus(Duration.ofMinutes(59)))));
        // Événement passé sans rappel : aucun rappel.
        raw.insertOne(legacy("Passé", startsAt.minus(Duration.ofDays(5))));

        assertThat(reminders.scheduleMissingReminders()).isEqualTo(4);
        assertThat(reminders.scheduleMissingReminders()).isZero(); // idempotent

        Document ancien = raw.find(new Document("title", "Ancien")).first();
        Document first = ancien.getList("reminders", Document.class).get(0);
        assertThat(first.getInteger("minutesBefore")).isEqualTo(60);
        assertThat(first.getDate("dueAt").toInstant()).isEqualTo(startsAt.minus(Duration.ofHours(1)));
        assertThat(first.getString("status")).isEqualTo("SCHEDULED");
        Document programme = raw.find(new Document("title", "Programmé")).first();
        assertThat(programme.containsKey("reminderDueAt")).isFalse();
        assertThat(programme.getList("reminders", Document.class).get(0).getString("status")).isEqualTo("SCHEDULED");
        Document envoye = raw.find(new Document("title", "Envoyé")).first();
        assertThat(envoye.containsKey("reminderSentAt")).isFalse();
        assertThat(envoye.getList("reminders", Document.class).get(0).getString("status")).isEqualTo("SENT");
        assertThat(raw.find(new Document("title", "Passé")).first().getList("reminders", Document.class)).isEmpty();
    }

    private static Document legacy(String title, Instant startsAt) {
        return new Document("title", title).append("startsAt", Date.from(startsAt)).append("timeZone", "UTC")
                .append("ownerId", "u1");
    }

    @Test
    void dueRemindersAreFoundThroughTheirIndexAndTheLegacyIndexIsDropped() {
        mongo.getCollection("events").createIndex(new Document("reminderDueAt", 1),
                new com.mongodb.client.model.IndexOptions().name(ReminderQueue.LEGACY_INDEX).sparse(true));
        reminders.scheduleMissingReminders();

        assertThat(mongo.indexOps(Event.class).getIndexInfo()).extracting(ix -> ix.getName())
                .contains("ix_events_reminders_due").doesNotContain(ReminderQueue.LEGACY_INDEX);
        Document plan = mongo.getCollection("events").find(new Document("reminders", new Document("$elemMatch",
                new Document("status", "SCHEDULED").append("dueAt", new Document("$lte", new Date()))))).explain();
        assertThat(plan.toJson()).contains("ix_events_reminders_due");
    }

    // --- Story 9 : plusieurs rappels par événement ----------------------------------------------------------

    @Autowired
    ReminderQueue queue;
    @Autowired
    com.reveil.device.DeviceService deviceService;
    @Autowired
    ReminderSettings settings;
    @Autowired
    com.reveil.webpush.WebPushService webPushService;
    @Autowired
    ReminderMonitor monitor;

    /** Planificateur à l'heure {@code at}. */
    private ReminderService at(Instant at) {
        return new ReminderService(queue, deviceService, push, webPushService, settings, monitor,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void eachConfiguredReminderIsSentOnceAtItsOwnTime() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Mariage", Duration.ofDays(10), "[10080,60,15]");
        Instant startsAt = events.findById(id).orElseThrow().getStartsAt();

        mvc.perform(get("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.reminders.length()").value(3))
                .andExpect(jsonPath("$.reminders[0].minutesBefore").value(10080))
                .andExpect(jsonPath("$.reminders[0].remindAt").value(startsAt.minus(Duration.ofDays(7)).toString()))
                .andExpect(jsonPath("$.reminders[1].minutesBefore").value(60))
                .andExpect(jsonPath("$.reminders[2].minutesBefore").value(15))
                .andExpect(jsonPath("$.reminders[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("scheduled"))));

        assertThat(at(startsAt.minus(Duration.ofDays(8))).sendDueReminders()).isZero();
        assertThat(at(startsAt.minus(Duration.ofDays(7))).sendDueReminders()).isEqualTo(1);
        assertThat(at(startsAt.minus(Duration.ofDays(6))).sendDueReminders()).isZero(); // pas de renvoi
        assertThat(at(startsAt.minus(Duration.ofMinutes(60))).sendDueReminders()).isEqualTo(1);
        assertThat(at(startsAt.minus(Duration.ofMinutes(15))).sendDueReminders()).isEqualTo(1);
        assertThat(at(startsAt.minus(Duration.ofMinutes(1))).sendDueReminders()).isZero();

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(push, times(3)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(PushMessage::body).containsExactly(
                "Commence dans 7 jours, le " + java.time.format.DateTimeFormatter.ofPattern("dd/MM")
                        .format(startsAt.atZone(ZoneOffset.UTC)) + " à "
                        + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(startsAt.atZone(ZoneOffset.UTC)),
                "Commence dans 1 h, à " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(startsAt.atZone(ZoneOffset.UTC)),
                "Commence dans 15 min, à " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(startsAt.atZone(ZoneOffset.UTC)));

        mvc.perform(get("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.reminders[*].status").value(org.hamcrest.Matchers.contains("sent", "sent", "sent")));
    }

    @Test
    void disabledRemindersAreNeverSent() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Discret", Duration.ofMinutes(10), "[]");

        mvc.perform(get("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.reminders").isEmpty());
        Instant startsAt = events.findById(id).orElseThrow().getStartsAt();
        assertThat(at(startsAt.minusSeconds(30)).sendDueReminders()).isZero();
        verify(push, never()).send(any());
    }

    @Test
    void severalOverdueRemindersOfTheSameEventGiveASingleNotification() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        // Dans 30 min : rappels « 1 semaine » et « 1 h » déjà échus, « 15 min » à venir.
        String id = createEvent(bearer(alice), "Rendez-vous", Duration.ofMinutes(30), "[10080,60,15]");

        assertThat(reminders.sendDueReminders()).isEqualTo(1);
        verify(push, times(1)).send(any());
        assertThat(events.findById(id).orElseThrow().getReminders()).extracting(Reminder::getStatus)
                .containsExactly(ReminderStatus.SENT, ReminderStatus.SENT, ReminderStatus.SCHEDULED);
    }

    @Test
    void changingTheChosenRemindersKeepsAlreadySentOnesAndSchedulesNewOnes() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Concert", Duration.ofMinutes(30), "[60]");
        assertThat(reminders.sendDueReminders()).isEqualTo(1);

        Event current = events.findById(id).orElseThrow();
        LocalDateTime same = LocalDateTime.ofInstant(current.getStartsAt(), ZoneOffset.UTC);
        mvc.perform(put("/api/events/" + id).header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"" + same.toLocalDate() + "\",\"time\":\""
                                + same.toLocalTime() + "\",\"timeZone\":\"UTC\",\"reminders\":[60,15]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminders[0].minutesBefore").value(60))
                .andExpect(jsonPath("$.reminders[0].status").value("sent"))
                .andExpect(jsonPath("$.reminders[1].minutesBefore").value(15))
                .andExpect(jsonPath("$.reminders[1].status").value("scheduled"));

        assertThat(reminders.sendDueReminders()).isZero(); // le rappel « 1 h » ne repart pas
        assertThat(at(current.getStartsAt().minus(Duration.ofMinutes(15))).sendDueReminders()).isEqualTo(1);
        verify(push, times(2)).send(any());

        // Désactivation de tous les rappels.
        mvc.perform(put("/api/events/" + id).header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"" + same.toLocalDate() + "\",\"time\":\""
                                + same.toLocalTime() + "\",\"timeZone\":\"UTC\",\"reminders\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminders").isEmpty());
    }

    @Test
    void concurrentSchedulersSendEachReminderOnlyOnce() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        for (int i = 0; i < 20; i++) {
            createEvent(bearer(alice), "Événement " + i, Duration.ofMinutes(30), "[60,15]");
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(n -> (java.util.concurrent.Callable<Integer>) () -> reminders.sendDueReminders()).toList();
            int total = 0;
            for (var f : pool.invokeAll(tasks)) {
                total += f.get();
            }
            assertThat(total).isEqualTo(20);
        } finally {
            pool.shutdown();
        }
        verify(push, times(20)).send(any());
    }

    @Test
    void remindersOfAnEventThatAlreadyStartedAreSkipped() throws Exception {
        JsonNode alice = register("alice@example.com");
        registerDevice(bearer(alice), "alice-phone");
        String id = createEvent(bearer(alice), "Raté", Duration.ofMinutes(30), "[60,15]");
        Instant startsAt = events.findById(id).orElseThrow().getStartsAt();

        assertThat(at(startsAt.plusSeconds(60)).sendDueReminders()).isZero(); // API arrêtée jusqu'après le début
        verify(push, never()).send(any());
        mvc.perform(get("/api/events/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.reminders[*].status").value(org.hamcrest.Matchers.contains("skipped", "skipped")));
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
