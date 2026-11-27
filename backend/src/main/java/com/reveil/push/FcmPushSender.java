package com.reveil.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envoi via l'API HTTP v1 de Firebase Cloud Messaging. Android : remise directe par FCM ;
 * iOS : FCM relaie vers APNs (clé APNs configurée dans le projet Firebase).
 * Le message porte un bloc « notification » : l'OS l'affiche lui-même, application fermée.
 */
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final HttpClient http;
    private final URI endpoint;
    private final Supplier<String> accessToken;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * @param baseUrl     {@code https://fcm.googleapis.com} (modifiable pour les tests)
     * @param accessToken jeton OAuth2 du compte de service (portée firebase.messaging)
     */
    public FcmPushSender(String baseUrl, String projectId, Supplier<String> accessToken, ObjectMapper json,
                         Clock clock, Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/projects/" + projectId + "/messages:send");
        this.accessToken = accessToken;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public PushResult send(PushMessage message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken.get())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(message))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushResult.FAILED;
        } catch (IOException | RuntimeException e) {
            log.warn("Envoi FCM impossible : {}", e.toString());
            return PushResult.FAILED;
        }
    }

    Map<String, Object> payload(PushMessage m) {
        long ttlSeconds = Math.max(0, Duration.between(clock.instant(), m.expiresAt()).toSeconds());
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH"); // remise immédiate, y compris en mode Doze
        android.put("ttl", ttlSeconds + "s");
        android.put("collapse_key", m.collapseKey());
        android.put("notification", Map.of("tag", m.collapseKey(), "sound", "default"));

        Map<String, Object> apns = Map.of(
                "headers", Map.of(
                        "apns-priority", "10",
                        "apns-push-type", "alert",
                        "apns-expiration", String.valueOf(m.expiresAt().getEpochSecond()),
                        "apns-collapse-id", m.collapseKey()),
                "payload", Map.of("aps", Map.of("sound", "default")));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", m.token());
        message.put("notification", Map.of("title", m.title(), "body", m.body()));
        message.put("data", m.data());
        message.put("android", android);
        message.put("apns", apns);
        return Map.of("message", message);
    }

    /**
     * Codes d'erreur FCM v1 : UNREGISTERED (404) et SENDER_ID_MISMATCH (403) → jeton à supprimer ;
     * INVALID_ARGUMENT (400) → jeton à supprimer seulement si c'est le jeton qui est en cause.
     * Le reste (401, 429, 5xx…) est un échec temporaire ou de configuration : le jeton est conservé.
     */
    PushResult interpret(int status, String body) {
        if (status >= 200 && status < 300) {
            return PushResult.SENT;
        }
        String code = "";
        String message = "";
        try {
            JsonNode error = json.readTree(body == null ? "" : body).path("error");
            message = error.path("message").asText("");
            code = error.path("status").asText("");
            for (JsonNode detail : error.path("details")) {
                if (detail.hasNonNull("errorCode")) {
                    code = detail.get("errorCode").asText();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // corps illisible : seul le statut HTTP est exploitable
        }
        boolean invalid = switch (code) {
            case "UNREGISTERED", "SENDER_ID_MISMATCH" -> true;
            case "INVALID_ARGUMENT" -> message.toLowerCase(Locale.ROOT).contains("registration token");
            default -> status == 404;
        };
        if (invalid) {
            return PushResult.INVALID_TOKEN;
        }
        log.warn("FCM a refusé l'envoi : HTTP {} {} {}", status, code, message);
        return PushResult.FAILED;
    }
}
