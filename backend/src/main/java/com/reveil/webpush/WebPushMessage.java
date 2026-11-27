package com.reveil.webpush;

import java.time.Instant;
import java.util.Map;

/**
 * Notification à afficher par le service worker du navigateur ({@code reveil-sw.js}).
 *
 * @param url       page ouverte au clic, relative à la portée du service worker (ex. {@code evenements/<id>})
 * @param tag       une notification plus récente de même étiquette remplace la précédente dans le navigateur
 * @param expiresAt au-delà, le service push abandonne la remise (navigateur resté hors ligne)
 * @param topic     remplacement côté service push d'un message encore en attente (≤ 32 caractères base64url)
 */
public record WebPushMessage(String title, String body, String url, String tag, Map<String, String> data,
                             Instant expiresAt, String topic) {
}
