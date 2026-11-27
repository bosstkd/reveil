package com.reveil.webpush;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages Web Push ({@code reveil.push.web.*}).
 *
 * @param publicKey    clé publique VAPID (base64url, 65 octets) ; avec {@code privateKey}. Absentes : une paire est
 *                     générée au premier besoin et conservée en base (collection {@code web_push_vapid_keys})
 * @param privateKey   clé privée VAPID (base64url, 32 octets) ; fournie par l'environnement, jamais dans le dépôt
 * @param subject      contact transmis aux services push (JWT VAPID {@code sub}) : {@code mailto:} ou URL https
 * @param allowedHosts services push acceptés pour l'adresse d'abonnement (hôte exact ou sous-domaine) : l'API
 *                     n'envoie de requête qu'à ces hôtes, en HTTPS
 * @param timeout      délai de connexion et de réponse d'un service push
 */
@ConfigurationProperties(prefix = "reveil.push.web")
public record WebPushSettings(
        @DefaultValue("") String publicKey,
        @DefaultValue("") String privateKey,
        @DefaultValue("mailto:no-reply@reveil.local") String subject,
        @DefaultValue({"fcm.googleapis.com", "android.googleapis.com", "push.services.mozilla.com",
                "notify.windows.com", "push.apple.com"}) List<String> allowedHosts,
        @DefaultValue("PT5S") Duration timeout) {
}
