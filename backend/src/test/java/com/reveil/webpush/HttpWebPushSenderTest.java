package com.reveil.webpush;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.push.PushResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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

/** Envoi Web Push face à un faux service push local ; le contenu est déchiffré comme le ferait le navigateur. */
class HttpWebPushSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-27T18:30:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
    final AtomicReference<String> receivedPath = new AtomicReference<>();
    volatile int status = 201;
    final VapidKeys keys = VapidKeys.generate();
    final TestBrowser browser = new TestBrowser();
    HttpWebPushSender sender;
    String endpoint;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedHeaders.set(exchange.getRequestHeaders());
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc";
        sender = new HttpWebPushSender(() -> keys, "mailto:admin@example.com", JSON, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static WebPushMessage message() {
        return new WebPushMessage("Rappel : Concert", "Commence dans 1 h, à 20:30 · Olympia",
                "evenements/e1", "reminder-e1", Map.of("type", "event-reminder", "eventId", "e1"),
                NOW.plus(Duration.ofHours(1)), "e1");
    }

    @Test
    void envoieUnContenuChiffreAuthentifieParVapid() throws Exception {
        assertThat(sender.send(browser.subscription(endpoint), message())).isEqualTo(PushResult.SENT);

        Headers h = receivedHeaders.get();
        assertThat(receivedPath.get()).isEqualTo("/push/abc");
        assertThat(h.getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(h.getFirst("TTL")).isEqualTo("3600");
        assertThat(h.getFirst("Urgency")).isEqualTo("high");
        assertThat(h.getFirst("Topic")).isEqualTo("e1");
        assertThat(h.getFirst("Authorization")).startsWith("vapid t=").endsWith(", k=" + keys.publicKey());

        JsonNode payload = JSON.readTree(browser.decrypt(receivedBody.get()));
        assertThat(payload.get("title").asText()).isEqualTo("Rappel : Concert");
        assertThat(payload.get("body").asText()).isEqualTo("Commence dans 1 h, à 20:30 · Olympia");
        assertThat(payload.get("url").asText()).isEqualTo("evenements/e1");
        assertThat(payload.get("tag").asText()).isEqualTo("reminder-e1");
        assertThat(payload.at("/data/eventId").asText()).isEqualTo("e1");
    }

    @ParameterizedTest
    @CsvSource({"201,SENT", "202,SENT", "404,INVALID_TOKEN", "410,INVALID_TOKEN", "400,FAILED", "403,FAILED",
            "413,FAILED", "429,FAILED", "503,FAILED"})
    void interpreteLaReponseDuServicePush(int code, PushResult expected) {
        status = code;
        assertThat(sender.send(browser.subscription(endpoint), message())).isEqualTo(expected);
    }

    @Test
    void servicePushInjoignableEchecSansException() {
        server.stop(0);
        assertThat(sender.send(browser.subscription(endpoint), message())).isEqualTo(PushResult.FAILED);
    }

    @Test
    void ttlNulPourUnEvenementDejaCommence() {
        WebPushMessage late = new WebPushMessage("t", "b", "evenements/e1", "reminder-e1", Map.of(),
                NOW.minusSeconds(30), null);
        assertThat(sender.send(browser.subscription(endpoint), late)).isEqualTo(PushResult.SENT);
        assertThat(receivedHeaders.get().getFirst("TTL")).isEqualTo("0");
        assertThat(receivedHeaders.get().containsKey("Topic")).isFalse();
    }
}
