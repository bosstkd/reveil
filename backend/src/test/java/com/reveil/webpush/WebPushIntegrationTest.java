package com.reveil.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import com.reveil.event.EventRepository;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import com.reveil.reminder.ReminderService;
import com.reveil.session.SessionRepository;
import com.reveil.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Notifications du navigateur de bout en bout : clé VAPID, abonnement, rappel envoyé, déconnexion (MongoDB embarqué). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebPushIntegrationTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/alice-chrome";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    SessionRepository sessions;
    @Autowired
    EventRepository events;
    @Autowired
    WebPushSubscriptionRepository subscriptions;
    @Autowired
    ReminderService reminders;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    WebPushSender sender;
    @MockitoBean
    PushSender push;

    @BeforeEach
    void clean() {
        users.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        subscriptions.deleteAll();
        reset(sender, push);
        when(sender.send(any(), any())).thenReturn(PushResult.SENT);
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

    private void subscribe(String bearer, Object body, int expected) throws Exception {
        mvc.perform(put("/api/push/web/subscription").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

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
    void cleVapidPubliqueStableEtProtegee() throws Exception {
        JsonNode alice = register("alice@example.com");
        String first = json.readTree(mvc.perform(get("/api/push/web/public-key").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("publicKey").asText();
        assertThat(EcKeys.base64Url(first)).hasSize(65).startsWith((byte) 0x04);
        mvc.perform(get("/api/push/web/public-key").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.publicKey").value(first));
        mvc.perform(get("/api/push/web/public-key")).andExpect(status().isUnauthorized());
    }

    @Test
    void abonnementEnregistreRattacheAuDernierCompteEtDesactivable() throws Exception {
        JsonNode alice = register("alice@example.com");
        JsonNode bob = register("bob@example.com");

        subscribe(bearer(alice), WebPushTestData.subscription(ENDPOINT), 204);
        subscribe(bearer(alice), WebPushTestData.subscription(ENDPOINT), 204); // idempotent (clés renouvelées)
        WebPushSubscription stored = subscriptions.findByEndpoint(ENDPOINT).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(alice.at("/user/id").asText());
        assertThat(stored.getSessionId()).isNotBlank();
        assertThat(subscriptions.count()).isEqualTo(1);

        // Bob ne peut pas désactiver le navigateur d'Alice.
        mvc.perform(delete("/api/push/web/subscription").header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions.findByEndpoint(ENDPOINT)).isPresent();

        // Même navigateur, Bob connecté ensuite : l'abonnement lui est rattaché.
        subscribe(bearer(bob), WebPushTestData.subscription(ENDPOINT), 204);
        assertThat(subscriptions.findByEndpoint(ENDPOINT).orElseThrow().getUserId()).isEqualTo(bob.at("/user/id").asText());

        mvc.perform(delete("/api/push/web/subscription").header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions.count()).isZero();
        subscribe("", WebPushTestData.subscription(ENDPOINT), 401);
    }

    @Test
    void abonnementInvalideRefuseEn400() throws Exception {
        String alice = bearer(register("alice@example.com"));
        Map<String, Object> valid = WebPushTestData.subscription(ENDPOINT);
        Object keys = valid.get("keys");

        for (String endpoint : new String[] {"http://fcm.googleapis.com/fcm/send/x", "https://127.0.0.1/push",
                "https://fcm.googleapis.com.evil.example/x", "https://user@fcm.googleapis.com/x", "pas une adresse"}) {
            mvc.perform(put("/api/push/web/subscription").header("Authorization", alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("endpoint", endpoint, "keys", keys))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("endpoint"));
        }
        mvc.perform(put("/api/push/web/subscription").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("endpoint", ENDPOINT,
                                "keys", Map.of("p256dh", "AAAA", "auth", "BTBZMqHH6r4Tts7J_aSIgg")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("keys.p256dh"));
        mvc.perform(put("/api/push/web/subscription").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("endpoint", ENDPOINT, "keys",
                                Map.of("p256dh", ((Map<?, ?>) keys).get("p256dh"), "auth", "court")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("keys.auth"));
        mvc.perform(put("/api/push/web/subscription").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("keys"));
        assertThat(subscriptions.count()).isZero();
    }

    @Test
    void rappelEnvoyeAuNavigateurAbonnePuisPlusApresDeconnexion() throws Exception {
        JsonNode alice = register("alice@example.com");
        JsonNode bob = register("bob@example.com");
        subscribe(bearer(alice), WebPushTestData.subscription(ENDPOINT), 204);
        subscribe(bearer(bob), WebPushTestData.subscription("https://updates.push.services.mozilla.com/wpush/v2/bob"), 204);

        String eventId = createEvent(bearer(alice), "Concert", Duration.ofMinutes(30)); // rappel 1 h : déjà dû
        assertThat(reminders.sendDueReminders()).isEqualTo(1);

        ArgumentCaptor<WebPushSubscription> to = ArgumentCaptor.forClass(WebPushSubscription.class);
        ArgumentCaptor<WebPushMessage> message = ArgumentCaptor.forClass(WebPushMessage.class);
        verify(sender).send(to.capture(), message.capture());
        assertThat(to.getValue().getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(message.getValue().title()).isEqualTo("Rappel : Concert");
        assertThat(message.getValue().url()).isEqualTo("evenements/" + eventId);

        // Déconnexion du navigateur : l'abonnement n'est plus utilisé (et disparaît au passage suivant).
        reset(sender);
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + alice.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());
        JsonNode again = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret123\"}"))
                .andReturn().getResponse().getContentAsString());
        createEvent(bearer(again), "Théâtre", Duration.ofMinutes(20));
        assertThat(reminders.sendDueReminders()).isEqualTo(1);
        verify(sender, never()).send(any(), any());
        assertThat(subscriptions.findByEndpoint(ENDPOINT)).isEmpty();
    }

    @Test
    void abonnementExpireOublieApresUn410() throws Exception {
        JsonNode alice = register("alice@example.com");
        subscribe(bearer(alice), WebPushTestData.subscription(ENDPOINT), 204);
        when(sender.send(any(), any())).thenReturn(PushResult.INVALID_TOKEN);

        createEvent(bearer(alice), "Concert", Duration.ofMinutes(30));
        reminders.sendDueReminders();

        assertThat(subscriptions.findByEndpoint(ENDPOINT)).isEmpty();
    }
}
