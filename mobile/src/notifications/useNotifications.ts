import { useCallback, useEffect, useState } from 'react';
import { Alert, Linking } from 'react-native';
import {
  PushMessage,
  askPermissionOnFirstLaunch,
  eventIdFromLink,
  eventIdFromMessage,
  pushEvents,
  registerDevice,
  sendDeviceToken,
} from './push';

type AuthFetch = (path: string, init?: RequestInit) => Promise<Response>;

/** Exécute `subscribe` sans faire échouer l'application si le module natif est absent. */
function safely(subscribe: () => () => void): () => void {
  try {
    return subscribe();
  } catch {
    return () => undefined;
  }
}

/**
 * Demande l'autorisation au premier lancement et détermine l'événement à ouvrir :
 * appui sur une notification (application fermée ou en arrière-plan) ou lien profond reveil://events/<id>.
 * Application au premier plan : l'OS n'affiche pas la notification, une alerte propose de l'ouvrir.
 */
export function useNotificationRouting() {
  const [eventId, setEventId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const open = (id: string | null) => {
      if (active && id) setEventId(id);
    };
    void askPermissionOnFirstLaunch();
    Promise.resolve()
      .then(() => pushEvents.initialNotification())
      .then((m) => open(eventIdFromMessage(m)), () => undefined);
    Linking.getInitialURL().then((url) => open(eventIdFromLink(url)), () => undefined);

    const unsubscribeOpened = safely(() => pushEvents.onOpened((m) => open(eventIdFromMessage(m))));
    const unsubscribeForeground = safely(() =>
      pushEvents.onForegroundMessage((m: PushMessage) => {
        const id = eventIdFromMessage(m);
        if (!id) return;
        Alert.alert(m.notification?.title ?? 'Rappel', m.notification?.body ?? '', [
          { text: 'Plus tard', style: 'cancel' },
          { text: 'Voir', onPress: () => open(id) },
        ]);
      }),
    );
    const link = Linking.addEventListener('url', ({ url }) => open(eventIdFromLink(url)));
    return () => {
      active = false;
      unsubscribeOpened();
      unsubscribeForeground();
      link.remove();
    };
  }, []);

  const close = useCallback(() => setEventId(null), []);
  return { eventId, close };
}

/** Utilisateur connecté : enregistre le jeton push de l'appareil, puis chacun de ses renouvellements. */
export function useDeviceRegistration(userId: string | undefined, authFetch: AuthFetch) {
  useEffect(() => {
    if (!userId) return;
    void registerDevice(authFetch);
    return safely(() => pushEvents.onTokenRefresh((token) => void sendDeviceToken(authFetch, token)));
  }, [userId, authFetch]);
}
