import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import App from '../../App';
import {
  ReveilEvent,
  combine,
  deviceTimeZone,
  eventDate,
  eventTime,
  formatDate,
  formatTime,
  isPast,
  mergeEvents,
  timeUntil,
} from '../events/events';

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
type Pages = Partial<Record<'upcoming' | 'past', object[][]>>;

const pageOf = (when: string, pages: object[][], page: number) => ({
  when,
  items: pages[page] ?? [],
  page,
  size: 100,
  totalElements: pages.flat().length,
  hasNext: page < pages.length - 1,
});

const isList = (url: string, init?: RequestInit) =>
  url.includes('/api/events?') && (!init?.method || init.method === 'GET');

/**
 * Répond selon la méthode et l'URL. GET /api/events?when=upcoming renvoie `initial` ;
 * `pages` permet de fournir plusieurs pages par onglet.
 */
function api(initial: object[], onPost?: Route, pages: Pages = {}) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (isList(url, init)) {
      const q = new URL(url).searchParams;
      const when = q.get('when') as 'upcoming' | 'past';
      const all = pages[when] ?? [when === 'upcoming' ? initial : []];
      return jsonResponse(200, pageOf(when, all, Number(q.get('page'))));
    }
    if (url.endsWith('/api/events') && init?.method === 'POST') return onPost?.(url, init) ?? jsonResponse(500, null);
    return jsonResponse(204, null);
  });
}

const listCalls = () => fetchMock.mock.calls.filter(([url, init]) => isList(url, init)).map(([url]) => url as string);

async function openForm() {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('create-first'));
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
  it('charge les événements à venir : titre, date, heure et temps restant', async () => {
    api([created()]);
    await render(<App />);
    expect(await screen.findByText('Concert')).toBeOnTheScreen();
    expect(screen.getByTestId('item-date')).toHaveTextContent(/24 décembre 2099/);
    expect(screen.getByTestId('item-time')).toHaveTextContent('20:30');
    expect(screen.getByTestId('item-remaining')).toHaveTextContent(/^dans \d+ ans$/);
    expect(screen.getByText('Olympia')).toBeOnTheScreen();
    expect(screen.queryByTestId('events-empty')).toBeNull();
    expect(screen.getByTestId('tab-upcoming')).toHaveTextContent('À venir (1)');
    expect(listCalls()).toEqual([expect.stringMatching(/\/api\/events\?when=upcoming&page=0&size=100$/)]);
  });

  it('sépare les événements passés dans un onglet chargé à la demande', async () => {
    api([created()], undefined, {
      past: [[created({ id: 'p1', title: 'Hier', startsAt: '2001-01-01T08:00:00Z', past: true })]],
    });
    await render(<App />);
    await screen.findByText('Concert');
    expect(listCalls()).toHaveLength(1);

    await fireEvent.press(screen.getByTestId('tab-past'));
    expect(await screen.findByText('Hier')).toBeOnTheScreen();
    expect(screen.queryByText('Concert')).toBeNull();
    expect(screen.getByTestId('item-remaining')).toHaveTextContent(/^il y a \d+ ans$/);
    expect(listCalls()[1]).toMatch(/when=past&page=0/);

    // Retour : onglet déjà chargé, aucune nouvelle requête.
    await fireEvent.press(screen.getByTestId('tab-upcoming'));
    expect(await screen.findByText('Concert')).toBeOnTheScreen();
    expect(listCalls()).toHaveLength(2);
  });

  it('charge la page suivante', async () => {
    api([], undefined, {
      upcoming: [[created({ id: 'a', title: 'A' })], [created({ id: 'b', title: 'B', startsAt: '2100-01-01T00:00:00Z' })]],
    });
    await render(<App />);
    await screen.findByText('A');
    await fireEvent.press(screen.getByTestId('load-more'));
    expect(await screen.findByText('B')).toBeOnTheScreen();
    expect(screen.getAllByTestId('item-title').map((n) => n.props.children)).toEqual(['A', 'B']);
    expect(screen.queryByTestId('load-more')).toBeNull();
    expect(listCalls()[1]).toMatch(/when=upcoming&page=1/);
  });

  it('affiche une liste de 100 événements (virtualisée) en moins de 2 secondes', async () => {
    const many = Array.from({ length: 100 }, (_, i) =>
      created({ id: `e${i}`, title: `Événement ${i}`, startsAt: new Date(Date.UTC(2099, 0, 1, i)).toISOString() }),
    );
    api(many);
    const start = Date.now();
    await render(<App />);
    expect(await screen.findByText('Événement 0')).toBeOnTheScreen();
    expect(Date.now() - start).toBeLessThan(2000);
    expect(screen.getByTestId('tab-upcoming')).toHaveTextContent('À venir (100)');
    expect(screen.getByTestId('event-list').props.data).toHaveLength(100);
  });

  it('état vide : bouton « Créer mon premier événement »', async () => {
    api([]);
    await render(<App />);
    expect(await screen.findByTestId('events-empty')).toHaveTextContent(/Aucun événement à venir/);
    expect(screen.getByTestId('create-first')).toHaveTextContent('Créer mon premier événement');
    expect(screen.queryByTestId('new-event')).toBeNull();

    await fireEvent.press(screen.getByTestId('create-first'));
    expect(screen.getByTestId('event-form')).toBeOnTheScreen();
    expect(screen.queryByTestId('create-first')).toBeNull();
  });

  it('signale une erreur de chargement', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(500, null));
    await render(<App />);
    expect(await screen.findByTestId('events-load-error')).toHaveTextContent('Impossible de charger vos événements.');
  });

  it('propose 4 délais de rappel (1 h par défaut), 3 au plus, du plus éloigné au plus proche', async () => {
    let body: Record<string, unknown> = {};
    api([], (_url, init) => {
      body = JSON.parse(String(init?.body));
      return jsonResponse(201, created());
    });
    await openForm();
    expect(screen.getByTestId('reminder-15')).toHaveTextContent('15 min avant');
    expect(screen.getByTestId('reminder-60')).toHaveTextContent('1 h avant', { exact: false });
    expect(screen.getByTestId('reminder-1440')).toHaveTextContent('1 jour avant');
    expect(screen.getByTestId('reminder-10080')).toHaveTextContent('1 semaine avant');
    expect(screen.getByTestId('reminder-60').props.accessibilityState).toMatchObject({ checked: true });

    await fireEvent.press(screen.getByTestId('reminder-15'));
    await fireEvent.press(screen.getByTestId('reminder-10080'));
    expect(screen.getByTestId('reminders-max')).toHaveTextContent('3 rappels maximum.');
    expect(screen.getByTestId('reminder-1440').props.accessibilityState).toMatchObject({ disabled: true });
    await fireEvent.press(screen.getByTestId('reminder-1440')); // ignoré : maximum atteint

    await fireEvent.changeText(screen.getByTestId('event-title'), 'Mariage');
    await pick('event-date', new Date(2099, 5, 20));
    await pick('event-time', new Date(2000, 0, 1, 15, 0));
    await fireEvent.press(screen.getByTestId('event-submit'));
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(body.reminders).toEqual([10080, 60, 15]);
  });

  it('permet de désactiver tous les rappels', async () => {
    let body: Record<string, unknown> = {};
    api([], (_url, init) => {
      body = JSON.parse(String(init?.body));
      return jsonResponse(201, created());
    });
    await openForm();
    await fireEvent.press(screen.getByTestId('reminders-disable'));
    expect(screen.getByTestId('reminders-off')).toHaveTextContent('Aucun rappel', { exact: false });

    await fireEvent.changeText(screen.getByTestId('event-title'), 'Discret');
    await pick('event-date', new Date(2099, 5, 20));
    await pick('event-time', new Date(2000, 0, 1, 15, 0));
    await fireEvent.press(screen.getByTestId('event-submit'));
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(body.reminders).toEqual([]);
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
      reminders: [60],
    });
    const [, init] = postCalls()[0];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer jwt');
    expect(screen.queryByTestId('event-form')).toBeNull();
    // Une seule lecture de la liste : l'ajout est local, sans rechargement.
    expect(listCalls()).toHaveLength(1);
    expect(screen.getByTestId('tab-upcoming')).toHaveTextContent('À venir (1)');
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
    expect(postCalls()).toHaveLength(1);
    // Rangé dans l'onglet « Passés ».
    expect(screen.queryByText('Hier')).toBeNull();
    await fireEvent.press(screen.getByTestId('tab-past'));
    expect(await screen.findByText('Hier')).toBeOnTheScreen();
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

  it('timeUntil : temps restant arrondi à l’unité inférieure', () => {
    const ref = new Date('2026-11-25T12:00:00Z');
    const at = (ms: number) => new Date(ref.getTime() + ms).toISOString();
    const H = 3_600_000;
    expect(timeUntil(at(20_000), ref)).toBe('maintenant');
    expect(timeUntil(at(90_000), ref)).toBe('dans 1 minute');
    expect(timeUntil(at(3 * H), ref)).toBe('dans 3 heures');
    expect(timeUntil(at(49 * H), ref)).toBe('dans 2 jours');
    expect(timeUntil(at(24 * 60 * H), ref)).toBe('dans 2 mois');
    expect(timeUntil(at(24 * 365 * H), ref)).toBe('dans 1 an');
    expect(timeUntil(at(-25 * H), ref)).toBe('il y a 1 jour');
    expect(timeUntil('n/a', ref)).toBe('');
  });

  it('date et heure dans le fuseau de saisie', () => {
    const e = { startsAt: '2026-12-24T19:30:00Z', localDateTime: '2026-12-24T20:30:00', timeZone: 'Europe/Paris' } as ReveilEvent;
    expect(eventDate(e)).toBe('jeudi 24 décembre 2026');
    expect(eventTime(e)).toBe('20:30');
    expect(eventTime({ ...e, timeZone: 'Mars/Olympus' })).toBe('20:30');
  });

  it('mergeEvents trie selon l’onglet et dédoublonne', () => {
    const a = { id: 'a', startsAt: '2026-01-01T00:00:00Z' } as ReveilEvent;
    const b = { id: 'b', startsAt: '2026-02-01T00:00:00Z' } as ReveilEvent;
    expect(mergeEvents('upcoming', [b], [a, b]).map((e) => e.id)).toEqual(['a', 'b']);
    expect(mergeEvents('past', [a], [b]).map((e) => e.id)).toEqual(['b', 'a']);
  });
});
