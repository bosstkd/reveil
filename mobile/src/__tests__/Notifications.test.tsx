import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import * as Messaging from '@react-native-firebase/messaging';
import { Alert, Linking, PermissionsAndroid, Platform } from 'react-native';
import App from '../../App';
import { PERMISSION_ASKED_KEY, eventIdFromLink, eventIdFromMessage } from '../notifications/push';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

jest.mock('@react-native-community/datetimepicker', () => {
  const { View } = jest.requireActual('react-native');
  return { __esModule: true, default: (props: object) => <View {...props} /> };
});

const store = (SecureStore as unknown as { __store: Map<string, string> }).__store;
const messaging = Messaging as unknown as {
  requestPermission: jest.Mock;
  getToken: jest.Mock;
  getInitialNotification: jest.Mock;
  __emit: (kind: 'opened' | 'message' | 'token', value: unknown) => void;
};
const fetchMock = jest.fn();

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const concert = {
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [],
};

const reminder = (eventId: string) => ({
  notification: { title: 'Rappel : Concert', body: 'Commence dans 1 h, à 20:30' },
  data: { type: 'event-reminder', eventId, link: `reveil://events/${eventId}` },
});

const SESSION = JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });

/** API simulée : liste vide, détail de e1, 404 pour tout autre événement. */
function api() {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [], page: 0, size: 100, totalElements: 0, hasNext: false });
    }
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, concert);
    if (url.includes('/api/events/')) return jsonResponse(404, { message: 'Événement introuvable', errors: [] });
    if (url.endsWith('/api/auth/login')) {
      return jsonResponse(200, { token: 'jwt', expiresIn: 900, refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });
    }
    if (url.endsWith('/api/devices') && init?.method === 'PUT') return jsonResponse(204, null);
    return jsonResponse(204, null);
  });
}

const deviceCalls = () =>
  fetchMock.mock.calls.filter(([url, init]) => url.endsWith('/api/devices') && init?.method === 'PUT');

beforeEach(() => {
  store.clear();
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  messaging.requestPermission.mockClear();
  messaging.getToken.mockClear();
  messaging.getInitialNotification.mockReset().mockResolvedValue(null);
  api();
});

describe('Autorisation des notifications', () => {
  it('est demandée au premier lancement, et seulement au premier', async () => {
    const first = await render(<App />);
    await waitFor(() => expect(messaging.requestPermission).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(store.get(PERMISSION_ASKED_KEY)).toBe('1'));
    await first.unmount();

    await render(<App />);
    await screen.findByTestId('login-submit');
    expect(messaging.requestPermission).toHaveBeenCalledTimes(1);
  });

  it('utilise la permission POST_NOTIFICATIONS sur Android 13+', async () => {
    const os = jest.replaceProperty(Platform, 'OS', 'android');
    const version = jest.spyOn(Platform, 'Version', 'get').mockReturnValue(34);
    const request = jest.spyOn(PermissionsAndroid, 'request').mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);
    try {
      await render(<App />);
      await waitFor(() => expect(request).toHaveBeenCalledWith(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS));
      expect(messaging.requestPermission).not.toHaveBeenCalled();
    } finally {
      request.mockRestore();
      os.restore();
      version.mockRestore();
    }
  });
});

describe('Enregistrement du jeton de l’appareil', () => {
  it('envoie le jeton FCM une fois connecté, puis à chaque renouvellement', async () => {
    await render(<App />);
    await screen.findByTestId('login-submit');
    expect(deviceCalls()).toHaveLength(0); // pas de session : rien n'est envoyé

    await fireEvent.changeText(screen.getByTestId('login-email'), 'alice@example.com');
    await fireEvent.changeText(screen.getByTestId('login-password'), 'motdepasse1');
    await fireEvent.press(screen.getByTestId('login-submit'));
    await screen.findByText('Mes événements');

    await waitFor(() => expect(deviceCalls()).toHaveLength(1));
    const [, init] = deviceCalls()[0];
    expect(JSON.parse(init.body)).toEqual({ token: 'fcm-token-1', platform: 'ios' });
    expect(init.headers.Authorization).toBe('Bearer jwt');

    await act(async () => messaging.__emit('token', 'fcm-token-2'));
    await waitFor(() => expect(deviceCalls()).toHaveLength(2));
    expect(JSON.parse(deviceCalls()[1][1].body).token).toBe('fcm-token-2');
  });
});

describe('Ouverture depuis une notification', () => {
  beforeEach(() => store.set('reveil.session', SESSION));

  it('application fermée : l’appui ouvre le détail de l’événement concerné', async () => {
    messaging.getInitialNotification.mockResolvedValue(reminder('e1'));
    await render(<App />);

    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert');
    expect(screen.getByTestId('detail-date')).toHaveTextContent(/24 décembre 2099/);
    expect(screen.getByTestId('detail-time')).toHaveTextContent('20:30');
    expect(screen.getByTestId('detail-location')).toHaveTextContent('Olympia');
    expect(fetchMock.mock.calls.some(([url]) => (url as string).endsWith('/api/events/e1'))).toBe(true);

    await fireEvent.press(screen.getByTestId('detail-back'));
    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
  });

  it('application en arrière-plan : l’appui ouvre le détail', async () => {
    await render(<App />);
    await screen.findByText('Mes événements');

    await act(async () => messaging.__emit('opened', reminder('e1')));
    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert');
  });

  it('lien profond reveil://events/<id>', async () => {
    jest.spyOn(Linking, 'getInitialURL').mockResolvedValueOnce('reveil://events/e1');
    await render(<App />);
    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert');
  });

  it('événement supprimé entre-temps : message explicite', async () => {
    messaging.getInitialNotification.mockResolvedValue(reminder('gone'));
    await render(<App />);
    expect(await screen.findByTestId('detail-error')).toHaveTextContent("Cet événement n'existe plus.");
  });

  it('application au premier plan : une alerte propose d’ouvrir l’événement', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
    await render(<App />);
    await screen.findByText('Mes événements');

    await act(async () => messaging.__emit('message', reminder('e1')));
    expect(alert).toHaveBeenCalledWith('Rappel : Concert', 'Commence dans 1 h, à 20:30', expect.any(Array));
    const buttons = alert.mock.calls[0][2] as { text: string; onPress?: () => void }[];
    await act(async () => buttons.find((b) => b.text === 'Voir')?.onPress?.());
    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert');
    alert.mockRestore();
  });

  it('non connecté : le détail s’ouvre après la connexion', async () => {
    store.delete('reveil.session');
    messaging.getInitialNotification.mockResolvedValue(reminder('e1'));
    await render(<App />);
    await screen.findByTestId('login-submit');

    await fireEvent.changeText(screen.getByTestId('login-email'), 'alice@example.com');
    await fireEvent.changeText(screen.getByTestId('login-password'), 'motdepasse1');
    await fireEvent.press(screen.getByTestId('login-submit'));
    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert');
  });
});

describe('Lecture des notifications et liens', () => {
  it('extrait l’identifiant de l’événement', () => {
    expect(eventIdFromLink('reveil://events/abc123')).toBe('abc123');
    expect(eventIdFromLink('reveil://events/abc123?from=push')).toBe('abc123');
    expect(eventIdFromLink('https://exemple.fr/events/abc')).toBeNull();
    expect(eventIdFromLink(null)).toBeNull();
    expect(eventIdFromMessage({ data: { eventId: 'e9' } })).toBe('e9');
    expect(eventIdFromMessage({ data: { link: 'reveil://events/e7' } })).toBe('e7');
    expect(eventIdFromMessage({ data: {} })).toBeNull();
    expect(eventIdFromMessage(null)).toBeNull();
  });
});
