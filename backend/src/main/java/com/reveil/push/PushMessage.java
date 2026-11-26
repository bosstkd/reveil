package com.reveil.push;

import java.time.Instant;
import java.util.Map;

/**
 * Notification à afficher sur un appareil.
 *
 * @param token     jeton FCM de l'appareil
 * @param data      données transmises à l'application (ex. {@code eventId}, {@code link}) pour l'ouverture
 * @param expiresAt au-delà, le service push abandonne la remise (appareil resté hors ligne)
 * @param collapseKey une notification plus récente de même clé remplace la précédente
 */
public record PushMessage(String token, String title, String body, Map<String, String> data, Instant expiresAt,
                          String collapseKey) {
}
