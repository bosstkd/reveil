import { InjectionToken } from '@angular/core';

/** Abonnement Web Push du navigateur, au format attendu par l'API (`PUT api/push/web/subscription`). */
export interface BrowserSubscription {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  /** Clé VAPID (base64url) avec laquelle l'abonnement a été créé ; null si inconnue. */
  applicationServerKey: string | null;
  unsubscribe(): Promise<boolean>;
}

/**
 * API du navigateur utilisées pour les notifications (Notification, service worker, PushManager), isolées
 * pour pouvoir être simulées dans les tests.
 */
export interface BrowserPush {
  /** Faux si le navigateur ne gère pas les notifications push (ou page servie hors HTTPS). */
  supported(): boolean;
  permission(): NotificationPermission;
  /** Demande l'autorisation : à appeler directement depuis un clic (exigé par certains navigateurs). */
  requestPermission(): Promise<NotificationPermission>;
  /** Abonnement existant de ce navigateur pour l'application, sans installer le service worker. */
  currentSubscription(): Promise<BrowserSubscription | null>;
  /** Installe le service worker (`reveil-sw.js`, portée = base href) puis abonne le navigateur. */
  subscribe(applicationServerKey: string): Promise<BrowserSubscription>;
}

/** Nom du service worker, relatif au base href (servi depuis `public/`). */
export const SERVICE_WORKER_URL = 'reveil-sw.js';

export const BROWSER_PUSH = new InjectionToken<BrowserPush>('BROWSER_PUSH', {
  providedIn: 'root',
  factory: () => new NativeBrowserPush(),
});

class NativeBrowserPush implements BrowserPush {
  supported(): boolean {
    return (
      typeof window !== 'undefined' &&
      window.isSecureContext &&
      'serviceWorker' in navigator &&
      'PushManager' in window &&
      'Notification' in window
    );
  }

  permission(): NotificationPermission {
    return Notification.permission;
  }

  requestPermission(): Promise<NotificationPermission> {
    return Notification.requestPermission();
  }

  async currentSubscription(): Promise<BrowserSubscription | null> {
    const registration = await navigator.serviceWorker.getRegistration(scopeUrl());
    const subscription = registration ? await registration.pushManager.getSubscription() : null;
    return subscription ? toBrowserSubscription(subscription) : null;
  }

  async subscribe(applicationServerKey: string): Promise<BrowserSubscription> {
    await navigator.serviceWorker.register(new URL(SERVICE_WORKER_URL, document.baseURI).href, {
      scope: scopeUrl(),
      updateViaCache: 'none',
    });
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(applicationServerKey),
    });
    return toBrowserSubscription(subscription);
  }
}

function scopeUrl(): string {
  return new URL('./', document.baseURI).href;
}

function toBrowserSubscription(subscription: PushSubscription): BrowserSubscription {
  const json = subscription.toJSON();
  const key = subscription.options?.applicationServerKey;
  return {
    endpoint: subscription.endpoint,
    keys: { p256dh: json.keys?.['p256dh'] ?? '', auth: json.keys?.['auth'] ?? '' },
    applicationServerKey: key ? bytesToBase64Url(new Uint8Array(key)) : null,
    unsubscribe: () => subscription.unsubscribe(),
  };
}

export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
