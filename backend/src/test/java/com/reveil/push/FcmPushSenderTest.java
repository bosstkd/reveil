package com.reveil.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Envoi FCM HTTP v1 face à un faux serveur FCM local. */
class FcmPushSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final AtomicReference<String> receivedAuth = new AtomicReference<>();
    final AtomicReference<String> receivedPath = new AtomicReference<>();
    volatile int status = 200;
    volatile String responseBody = "{\"name\":\"projects/demo/messages/1\"}";
    FcmPushSender sender;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        sender = new FcmPushSender("http://127.0.0.1:" + server.getAddress().getPort() + "/", "demo",
                () -> "oauth-token", JSON, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static PushMessage message() {
        return new PushMessage("device-1", "Rappel : Concert", "Commence dans 1 h, à 20:30",
                Map.of("type", "event-reminder", "eventId", "e1", "link", "reveil://events/e1"),
                NOW.plus(Duration.ofHours(1)), "reminder-e1");
    }

    @Test
    void sendsAHighPriorityNotificationMessageToTheDeviceToken() throws Exception {
        assertThat(sender.send(message())).isEqualTo(PushResult.SENT);

        assertThat(receivedPath.get()).isEqualTo("/v1/projects/demo/messages:send");
        assertThat(receivedAuth.get()).isEqualTo("Bearer oauth-token");
        JsonNode m = JSON.readTree(receivedBody.get()).get("message");
        assertThat(m.get("token").asText()).isEqualTo("device-1");
        // Bloc « notification » : affiché par l'OS même application fermée.
        assertThat(m.at("/notification/title").asText()).isEqualTo("Rappel : Concert");
        assertThat(m.at("/notification/body").asText()).isEqualTo("Commence dans 1 h, à 20:30");
        assertThat(m.at("/data/eventId").asText()).isEqualTo("e1");
        assertThat(m.at("/data/link").asText()).isEqualTo("reveil://events/e1");
        assertThat(m.at("/android/priority").asText()).isEqualTo("HIGH");
        assertThat(m.at("/android/ttl").asText()).isEqualTo("3600s");
        assertThat(m.at("/apns/headers/apns-priority").asText()).isEqualTo("10");
        assertThat(m.at("/apns/headers/apns-push-type").asText()).isEqualTo("alert");
        assertThat(m.at("/apns/headers/apns-expiration").asText())
                .isEqualTo(String.valueOf(NOW.plus(Duration.ofHours(1)).getEpochSecond()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "404|{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"Requested entity was not found.\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}|INVALID_TOKEN",
            "400|{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"The registration token is not a valid FCM registration token\",\"details\":[{\"errorCode\":\"INVALID_ARGUMENT\"}]}}|INVALID_TOKEN",
            "403|{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\",\"message\":\"SenderId mismatch\",\"details\":[{\"errorCode\":\"SENDER_ID_MISMATCH\"}]}}|INVALID_TOKEN",
            "400|{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"Invalid value at 'message.android.ttl'\"}}|FAILED",
            "401|{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\",\"message\":\"Request had invalid authentication credentials.\"}}|FAILED",
            "429|{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"errorCode\":\"QUOTA_EXCEEDED\"}]}}|FAILED",
            "503|{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"details\":[{\"errorCode\":\"UNAVAILABLE\"}]}}|FAILED",
            "500|pas du json|FAILED",
    })
    void mapsFcmErrorsToInvalidTokenOrTransientFailure(int code, String body, PushResult expected) {
        status = code;
        responseBody = body;
        assertThat(sender.send(message())).isEqualTo(expected);
    }

    @Test
    void unreachableServerIsATransientFailure() {
        server.stop(0);
        assertThat(sender.send(message())).isEqualTo(PushResult.FAILED);
    }

    @Test
    void expiredEventGivesAZeroTtl() {
        PushMessage late = new PushMessage("d", "t", "b", Map.of(), NOW.minusSeconds(10), "k");
        @SuppressWarnings("unchecked")
        Map<String, Object> android = (Map<String, Object>) ((Map<String, Object>) sender.payload(late).get("message"))
                .get("android");
        assertThat(android.get("ttl")).isEqualTo("0s");
    }
}
