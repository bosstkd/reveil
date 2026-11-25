import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import App from '../../App';
import { combine, deviceTimeZone, formatDate, formatTime, isPast } from '../events/events';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

// Sélecteur natif remplacé par une vue : le test déclenche lui-même onChange comme le ferait l'OS.
jest.mock('@react-native-community/datetimepicker', () => {
  const { View } = jest.requireActual('react-native');
  const Picker = (props: object) => <View {...props} />;
  return { __esModule: true, default: Picker };
});

const store = (SecureStore as unknown as { __store: Map<string, string> }).__store;
const fetchMock = jest.fn();

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const created = (over: object = {}) => ({
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [],
  ...over,
});

type Route = (url: string, init?: RequestInit) => Response | undefined;

/** Répond selon la méthode et l'URL ; GET /api/events renvoie `initial` par défaut. */
function api(initial: unknown[], onPost?: Route) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/api/events') && (!init?.method || init.method === 'GET')) return jsonResponse(200, initial);
    if (url.endsWith('/api/events') && init?.method === 'POST') return onPost?.(url, init) ?? jsonResponse(500, null);
    return jsonResponse(204, null);
  });
}

async function openForm() {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('new-event'));
}

async function pick(field: 'event-date' | 'event-time', value: Date) {
  await fireEvent.press(screen.getByTestId(field));
  await act(async () => {
    fireEvent(screen.getByTestId('event-picker'), 'change', { type: 'set', nativeEvent: {} }, value);
  });
}

const postCalls = () =>
  fetchMock.mock.calls.filter(([url, init]) => url.endsWith('/api/events') && init?.method === 'POST');

beforeEach(() => {
  store.clear();
  store.set(
    'reveil.session',
    JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } }),
  );
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('Événements (mobile)', () => {
  it('charge la liste des événements de l’utilisateur', async () => {
    api([created()]);
    await render(<App />);
    expect(await screen.findByText('Concert')).toBeOnTheScreen();
    expect(screen.getByText('Olympia')).toBeOnTheScreen();
    expect(screen.queryByTestId('events-empty')).toBeNull();
  });

  it('bloque la création sans titre, date ni heure', async () => {
    api([]);
    await openForm();
    await fireEvent.press(screen.getByTestId('event-submit'));

    expect(screen.getByTestId('title-error')).toHaveTextContent('Le titre est obligatoire.');
    expect(screen.getByTestId('date-error')).toHaveTextContent('La date est obligatoire.');
    expect(screen.getByTestId('time-error')).toHaveTextContent("L'heure est obligatoire.");
    expect(postCalls()).toHaveLength(0);
  });

  it('crée l’événement avec le fuseau de l’appareil et l’affiche immédiatement', async () => {
    let body: Record<string, unknown> = {};
    api([], (_url, init) => {
      body = JSON.parse(String(init?.body));
      return jsonResponse(201, created());
    });
    await openForm();
    expect(screen.getByTestId('event-timezone')).toHaveTextContent(`Fuseau horaire : ${deviceTimeZone()}`);

    await fireEvent.changeText(screen.getByTestId('event-title'), '  Concert ');
    await pick('event-date', new Date(2099, 11, 24));
    await pick('event-time', new Date(2000, 0, 1, 20, 30));
    await fireEvent.changeText(screen.getByTestId('event-location'), ' Olympia ');
    expect(screen.queryByTestId('past-warning')).toBeNull();
    await fireEvent.press(screen.getByTestId('event-submit'));

    expect(await screen.findByTestId('event-item')).toHaveTextContent(/Concert/);
    expect(body).toEqual({
      title: 'Concert',
      date: '2099-12-24',
      time: '20:30',
      timeZone: deviceTimeZone(),
      location: 'Olympia',
    });
    const [, init] = postCalls()[0];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer jwt');
    expect(screen.queryByTestId('event-form')).toBeNull();
    // Une seule lecture de la liste : l'ajout est local, sans rechargement.
    expect(fetchMock.mock.calls.filter(([u, i]) => u.endsWith('/api/events') && !i?.method)).toHaveLength(1);
  });

  it('avertit pour une date passée mais accepte la création', async () => {
    api([], () => jsonResponse(201, created({ title: 'Hier', past: true, warnings: ['La date de cet événement est déjà passée'] })));
    await openForm();
    await fireEvent.changeText(screen.getByTestId('event-title'), 'Hier');
    await pick('event-date', new Date(2001, 0, 1));
    await pick('event-time', new Date(2000, 0, 1, 8, 0));

    expect(screen.getByTestId('past-warning')).toHaveTextContent(/déjà passée/);
    await fireEvent.press(screen.getByTestId('event-submit'));

    expect(await screen.findByTestId('created-notice')).toHaveTextContent(/déjà passée/);
    expect(screen.getByText('Passé')).toBeOnTheScreen();
    expect(postCalls()).toHaveLength(1);
  });

  it('affiche les erreurs de validation du serveur', async () => {
    api([], () =>
      jsonResponse(400, { message: 'Données invalides', errors: [{ field: 'timeZone', message: 'Le fuseau horaire est inconnu' }] }),
    );
    await openForm();
    await fireEvent.changeText(screen.getByTestId('event-title'), 'Concert');
    await pick('event-date', new Date(2099, 0, 1));
    await pick('event-time', new Date(2000, 0, 1, 9, 0));
    await fireEvent.press(screen.getByTestId('event-submit'));

    await waitFor(() => expect(screen.getByTestId('event-error')).toHaveTextContent('Le fuseau horaire est inconnu'));
    expect(screen.getByTestId('event-form')).toBeOnTheScreen();
  });
});

describe('events (utilitaires)', () => {
  it('formate date et heure locales', () => {
    expect(formatDate(new Date(2026, 0, 5))).toBe('2026-01-05');
    expect(formatTime(new Date(2026, 0, 5, 7, 3))).toBe('07:03');
    expect(combine(new Date(2026, 0, 5), new Date(2000, 1, 1, 7, 3))).toEqual(new Date(2026, 0, 5, 7, 3));
  });

  it('isPast compare à maintenant', () => {
    const now = new Date(2026, 10, 25, 12, 0);
    expect(isPast(new Date(2026, 10, 25), new Date(0, 0, 1, 11, 59), now)).toBe(true);
    expect(isPast(new Date(2026, 10, 25), new Date(0, 0, 1, 12, 1), now)).toBe(false);
    expect(isPast(null, new Date(), now)).toBe(false);
  });
});
