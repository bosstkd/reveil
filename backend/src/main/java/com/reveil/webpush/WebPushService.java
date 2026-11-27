package com.reveil.webpush;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.reveil.push.PushResult;
import com.reveil.session.SessionService;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Abonnements Web Push des navigateurs, par utilisateur, et envoi des notifications (story 14). */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    private final WebPushSubscriptionRepository subscriptions;
    private final MongoTemplate mongo;
    private final SessionService sessions;
    private final WebPushSender sender;
    private final VapidKeyStore keys;
    private final WebPushSettings settings;
    private final Clock clock;

    @Autowired
    public WebPushService(WebPushSubscriptionRepository subscriptions, MongoTemplate mongo, SessionService sessions,
                          WebPushSender sender, VapidKeyStore keys, WebPushSettings settings) {
        this(subscriptions, mongo, sessions, sender, keys, settings, Clock.systemUTC());
    }

    WebPushService(WebPushSubscriptionRepository subscriptions, MongoTemplate mongo, SessionService sessions,
                   WebPushSender sender, VapidKeyStore keys, WebPushSettings settings, Clock clock) {
        this.subscriptions = subscriptions;
        this.mongo = mongo;
        this.sessions = sessions;
        this.sender = sender;
        this.keys = keys;
        this.settings = settings;
        this.clock = clock;
    }

    /** Clé publique VAPID à transmettre au navigateur ({@code applicationServerKey}). */
    public String publicKey() {
        return keys.keys().publicKey();
    }

    /**
     * Enregistre (ou rattache) l'abonnement à l'utilisateur et à la session courants. Idempotent ; une adresse déjà
     * connue (autre compte connecté auparavant sur ce navigateur) change de propriétaire. Écriture atomique (upsert).
     */
    public void subscribe(String userId, String sessionId, WebPushSubscriptionRequest request) {
        String endpoint = checkEndpoint(request.endpoint());
        String p256dh = request.keys().p256dh().trim();
        String auth = request.keys().auth().trim();
        checkKeys(p256dh, auth);
        Query query = Query.query(where("endpoint").is(endpoint));
        Update update = new Update()
                .set("p256dh", p256dh)
                .set("auth", auth)
                .set("userId", userId)
                .set("sessionId", sessionId)
                .set("updatedAt", clock.instant())
                .setOnInsert("createdAt", clock.instant());
        for (int attempt = 0; ; attempt++) {
            try {
                mongo.upsert(query, update, WebPushSubscription.class);
                return;
            } catch (DuplicateKeyException e) {
                // Deux enregistrements simultanés de la même adresse : le second met à jour le document créé.
                if (attempt >= 1) {
                    throw e;
                }
            }
        }
    }

    /** Désactive les notifications du navigateur (seulement si l'abonnement appartient à l'utilisateur). Idempotent. */
    public void unsubscribe(String userId, String endpoint) {
        subscriptions.deleteByEndpointAndUserId(endpoint.trim(), userId);
    }

    /**
     * Abonnements utilisables : ceux dont la session est encore active. Les autres (navigateur déconnecté, session
     * expirée ou révoquée) sont supprimés au passage.
     */
    public List<WebPushSubscription> activeSubscriptionsOf(String userId) {
        return subscriptions.findByUserId(userId).stream()
                .filter(s -> {
                    if (sessions.isActive(s.getSessionId())) {
                        return true;
                    }
                    subscriptions.delete(s);
                    return false;
                })
                .toList();
    }

    /**
     * Envoie la notification à tous les navigateurs abonnés de l'utilisateur ; les abonnements refusés
     * définitivement (404/410) sont oubliés. Renvoie le nombre de navigateurs atteints.
     */
    public int notify(String userId, WebPushMessage message) {
        int sent = 0;
        for (WebPushSubscription subscription : activeSubscriptionsOf(userId)) {
            PushResult result = sender.send(subscription, message);
            if (result == PushResult.SENT) {
                sent++;
            } else if (result == PushResult.INVALID_TOKEN) {
                subscriptions.deleteByEndpoint(subscription.getEndpoint());
                log.info("Abonnement Web Push expiré supprimé");
            } else {
                log.warn("Notification Web Push non remise à un navigateur");
            }
        }
        return sent;
    }

    /** L'API n'envoie de requêtes qu'en HTTPS vers un service push connu (pas d'envoi vers une adresse arbitraire). */
    String checkEndpoint(String raw) {
        String endpoint = raw.trim();
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new InvalidWebPushSubscriptionException("endpoint", "Adresse d'abonnement invalide");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isEmpty() || uri.getRawUserInfo() != null) {
            throw new InvalidWebPushSubscriptionException("endpoint", "Adresse d'abonnement invalide");
        }
        boolean allowed = settings.allowedHosts().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .anyMatch(h -> !h.isEmpty() && (host.equals(h) || host.endsWith("." + h)));
        if (!allowed) {
            throw new InvalidWebPushSubscriptionException("endpoint", "Service de notification non pris en charge");
        }
        return endpoint;
    }

    private static void checkKeys(String p256dh, String auth) {
        try {
            EcKeys.publicKey(EcKeys.base64Url(p256dh));
        } catch (IllegalArgumentException e) {
            throw new InvalidWebPushSubscriptionException("keys.p256dh", "La clé p256dh est invalide");
        }
        byte[] secret;
        try {
            secret = EcKeys.base64Url(auth);
        } catch (IllegalArgumentException e) {
            secret = new byte[0];
        }
        if (secret.length != 16) {
            throw new InvalidWebPushSubscriptionException("keys.auth", "Le secret auth est invalide");
        }
    }
}
