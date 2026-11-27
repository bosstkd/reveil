import * as SecureStore from 'expo-secure-store';
import {
  AuthorizationStatus,
  getInitialNotification,
  getMessaging,
  getToken,
  onMessage,
  onNotificationOpenedApp,
  onTokenRefresh,
  requestPermission,
} from '@react-native-firebase/messaging';
import { PermissionsAndroid, Platform } from 'react-native';

type AuthFetch = (path: string, init?: RequestInit) => Promise<Response>;

/** Message push reçu (sous-ensemble utile de RemoteMessage). */
export interface PushMessage {
  notification?: { title?: string; body?: string };
  data?: Record<string, unknown>;
}

/** Marque « autorisation déjà demandée » : la demande n'a lieu qu'au premier lancement. */
export const PERMISSION_ASKED_KEY = 'reveil.notifications.asked';

/** Schéma de lien profond de l'application : reveil://events/<id>. */
const EVENT_LINK = /^reveil:\/\/events\/([^/?#]+)/;

/** Identifiant d'événement d'un lien profond `reveil://events/<id>` (null sinon). */
export function eventIdFromLink(url: string | null | undefined): string | null {
  const match = url ? EVENT_LINK.exec(url) : null;
  return match ? decodeURIComponent(match[1]) : null;
}

/** Événement visé par une notification de rappel (`data.eventId`, à défaut `data.link`). */
export function eventIdFromMessage(message: PushMessage | null | undefined): string | null {
  const data = message?.data ?? {};
  if (typeof data.eventId === 'string' && data.eventId) return data.eventId;
  return typeof data.link === 'string' ? eventIdFromLink(data.link) : null;
}

/** Demande système d'autorisation (Android 13+ : POST_NOTIFICATIONS ; iOS : via FCM/APNs). */
export async function requestNotificationPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    if (Number(Platform.Version) < 33) return true; // autorisation implicite avant Android 13
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    return result === PermissionsAndroid.RESULTS.GRANTED;
  }
  const status = await requestPermission(getMessaging());
  return status === AuthorizationStatus.AUTHORIZED || status === AuthorizationStatus.PROVISIONAL;
}

/** Au premier lancement uniquement : demande l'autorisation d'envoyer des notifications. */
export async function askPermissionOnFirstLaunch(): Promise<void> {
  const asked = await SecureStore.getItemAsync(PERMISSION_ASKED_KEY).catch(() => null);
  if (asked) return;
  try {
    await requestNotificationPermission();
  } catch {
    // module natif indisponible (ex. Expo Go) : l'application fonctionne sans rappels
  }
  await SecureStore.setItemAsync(PERMISSION_ASKED_KEY, '1').catch(() => undefined);
}

/** PUT /api/devices : rattache le jeton push de l'appareil à l'utilisateur connecté. */
export async function sendDeviceToken(authFetch: AuthFetch, token: string): Promise<boolean> {
  try {
    const res = await authFetch('/api/devices', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, platform: Platform.OS === 'android' ? 'android' : 'ios' }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/** Récupère le jeton FCM (sur iOS, FCM le relie au jeton APNs) et l'enregistre côté serveur. */
export async function registerDevice(authFetch: AuthFetch): Promise<boolean> {
  let token: string;
  try {
    token = await getToken(getMessaging());
  } catch {
    return false; // pas de Google Play Services / APNs indisponible : sans rappels
  }
  return token ? sendDeviceToken(authFetch, token) : false;
}

/** Abonnements FCM ; chaque fonction renvoie sa désinscription. */
export const pushEvents = {
  initialNotification: (): Promise<PushMessage | null> => getInitialNotification(getMessaging()),
  onOpened: (listener: (m: PushMessage) => void) => onNotificationOpenedApp(getMessaging(), listener),
  onForegroundMessage: (listener: (m: PushMessage) => void) => onMessage(getMessaging(), listener),
  onTokenRefresh: (listener: (token: string) => void) => onTokenRefresh(getMessaging(), listener),
};
