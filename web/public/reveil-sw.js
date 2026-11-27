/*
 * Service worker de Reveil (story 14) : affiche les rappels reçus par Web Push en notification système,
 * même quand aucun onglet de l'application n'est ouvert, et ouvre l'événement au clic.
 * Aucun cache : l'application reste servie normalement par nginx.
 * Portée = dossier du script (base href, ex. /test/reveil/) : les liens sont résolus par rapport à elle.
 */
'use strict';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

/** Contenu envoyé par l'API (HttpWebPushSender) : { title, body, url, tag, data }. */
function readPayload(data) {
  try {
    const payload = data ? data.json() : null;
    return payload && typeof payload === 'object' ? payload : {};
  } catch (e) {
    return { body: data ? data.text() : '' };
  }
}

/** Adresse à ouvrir, toujours dans la portée de l'application (jamais un site externe). */
function targetUrl(relative) {
  const scope = self.registration.scope;
  try {
    const url = new URL(typeof relative === 'string' ? relative : '', scope);
    return url.href.startsWith(scope) ? url.href : scope;
  } catch (e) {
    return scope;
  }
}

self.addEventListener('push', (event) => {
  const payload = readPayload(event.data);
  const title = payload.title || 'Reveil';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: payload.body || '',
      tag: payload.tag || undefined,
      icon: new URL('favicon.ico', self.registration.scope).href,
      lang: 'fr',
      // Rappel : reste affiché jusqu'à ce que l'utilisateur le voie (navigateurs qui le permettent).
      requireInteraction: true,
      data: { url: targetUrl(payload.url), ...(payload.data || {}) },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = targetUrl(event.notification.data && event.notification.data.url);
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => w.url.startsWith(self.registration.scope));
      if (open) {
        // navigate() échoue sur un onglet non contrôlé par ce service worker : nouvel onglet à la place.
        return open
          .focus()
          .then((w) => w.navigate(url))
          .catch(() => self.clients.openWindow(url));
      }
      return self.clients.openWindow(url);
    }),
  );
});
