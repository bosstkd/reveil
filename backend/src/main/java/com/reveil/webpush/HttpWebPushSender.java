package com.reveil.webpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.push.PushResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envoi Web Push (RFC 8030) : contenu chiffré pour le navigateur (RFC 8291), authentifié par VAPID (RFC 8292).
 * Le service worker l'affiche en notification système, même onglet fermé.
 */
public class HttpWebPushSender implements WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(HttpWebPushSender.class);

    private final HttpClient http;
    private final Supplier<VapidKeys> keys;
    private final String subject;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;

    public HttpWebPushSender(Supplier<VapidKeys> keys, String subject, ObjectMapper json, Clock clock, Duration timeout) {
        // Pas de suivi des redirections : la requête ne part que vers l'hôte autorisé de l'abonnement.
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.keys = keys;
        this.subject = subject;
        this.json = json;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public PushResult send(WebPushSubscription subscription, WebPushMessage message) {
        try {
            URI endpoint = URI.create(subscription.getEndpoint());
            byte[] body = WebPushEncryption.encrypt(json.writeValueAsBytes(payload(message)),
                    EcKeys.base64Url(subscription.getP256dh()), EcKeys.base64Url(subscription.getAuth()));
            long ttl = Math.max(0, Duration.between(clock.instant(), message.expiresAt()).toSeconds());
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout.plusSeconds(5))
                    .header("Authorization", keys.get().authorization(endpoint, subject, clock.instant()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(ttl))
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (message.topic() != null) {
                request.header("Topic", message.topic());
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushResult.FAILED;
        } catch (IOException | RuntimeException e) {
            log.warn("Envoi Web Push impossible : {}", e.toString());
            return PushResult.FAILED;
        }
    }

    /** Contenu lu par {@code reveil-sw.js} ({@code title}, {@code body}, {@code url}, {@code tag}, {@code data}). */
    static Map<String, Object> payload(WebPushMessage m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", m.title());
        payload.put("body", m.body());
        payload.put("url", m.url());
        payload.put("tag", m.tag());
        payload.put("data", m.data());
        return payload;
    }

    /**
     * 2xx : accepté. 404 / 410 : abonnement expiré ou résilié par le navigateur, à supprimer. Le reste (400, 403 :
     * VAPID refusé, 413, 429, 5xx…) est un échec de configuration ou temporaire : l'abonnement est conservé.
     */
    static PushResult interpret(int status, String body) {
        if (status >= 200 && status < 300) {
            return PushResult.SENT;
        }
        if (status == 404 || status == 410) {
            return PushResult.INVALID_TOKEN;
        }
        String detail = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
        log.warn("Le service Web Push a refusé l'envoi : HTTP {} {}", status, detail);
        return PushResult.FAILED;
    }
}
