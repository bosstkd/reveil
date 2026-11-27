import { act, fireEvent, render, screen } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import App from '../../App';
import { reminderDelay, reminderLabel } from '../events/events';
import type { ReveilEvent } from '../events/events';
import { CLOCK_TICK_MS } from '../screens/EventDetailScreen';

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
const fetchMock = jest.fn();

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const SESSION = JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });

const concert: ReveilEvent = {
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [],
  reminders: [{ minutesBefore: 60, remindAt: '2099-12-24T18:30:00Z', status: 'scheduled' }],
};

const photo = (id: string) => ({
  id,
  eventId: 'e1',
  contentType: 'image/jpeg',
  size: 812_000,
  width: 2048,
  height: 1536,
  createdAt: '2099-12-24T20:00:00Z',
  url: `/api/events/e1/photos/${id}/content`,
  thumbnailUrl: `/api/events/e1/photos/${id}/thumbnail`,
});

function api(detail: ReveilEvent, gallery: object[]) {
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [detail], page: 0, size: 100, totalElements: 1, hasNext: false });
    }
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, detail);
    if (url.endsWith('/api/events/e1/photos')) return jsonResponse(200, gallery);
    return jsonResponse(204, null);
  });
}

async function openConcert() {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('event-item'));
  await screen.findByTestId('detail-title');
}

beforeEach(() => {
  store.clear();
  store.set('reveil.session', SESSION);
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => jest.useRealTimers());

describe('Libellés des rappels', () => {
  it('formate le délai avant l’événement', () => {
    expect(reminderDelay(60)).toBe('1 h avant');
    expect(reminderDelay(30)).toBe('30 min avant');
    expect(reminderDelay(90)).toBe('1 h 30 min avant');
    expect(reminderDelay(1440)).toBe('1 jour avant');
    expect(reminderDelay(2880)).toBe('2 jours avant');
    expect(reminderDelay(0)).toBe('Au début');
  });

  it('affiche l’échéance dans le fuseau de saisie et le statut', () => {
    const label = reminderLabel(concert, concert.reminders[0]);
    expect(label).toMatch(/^1 h avant · /);
    expect(label).toContain('19:30'); // 18:30 UTC = 19:30 à Paris
    expect(label).toMatch(/programmé$/);
    expect(reminderLabel(concert, { ...concert.reminders[0], status: 'sent' })).toMatch(/envoyé$/);
    expect(reminderLabel(concert, { ...concert.reminders[0], status: 'skipped' })).toMatch(/non envoyé$/);
  });
});

describe('Détail d’un événement', () => {
  it('affiche titre, date, heure, lieu, temps restant et rappels configurés', async () => {
    api(concert, []);
    await openConcert();

    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/api/events/e1'))).toBe(true);
    expect(screen.getByTestId('detail-title')).toHaveTextContent('Concert');
    expect(screen.getByTestId('detail-date')).toHaveTextContent(/24 décembre 2099/);
    expect(screen.getByTestId('detail-time')).toHaveTextContent('20:30');
    expect(screen.getByTestId('detail-location')).toHaveTextContent('Olympia');
    expect(screen.getByTestId('detail-remaining')).toHaveTextContent(/^dans /);
    const reminders = screen.getAllByTestId('detail-reminder');
    expect(reminders).toHaveLength(1);
    expect(reminders[0]).toHaveTextContent(/1 h avant · .*19:30.* · programmé/);
  });

  it('sans rappel : l’indique', async () => {
    api({ ...concert, reminders: [] }, []);
    await openConcert();
    expect(screen.getByTestId('reminders-empty')).toHaveTextContent('Aucun rappel configuré.');
    expect(screen.queryByTestId('detail-reminder')).toBeNull();
  });

  it('affiche les photos en galerie de miniatures authentifiées', async () => {
    api(concert, [photo('p2'), photo('p1')]);
    await openConcert();

    const thumbs = await screen.findAllByTestId('gallery-photo');
    expect(thumbs).toHaveLength(2);
    expect(thumbs[0].props.source).toEqual({
      uri: expect.stringMatching(/\/api\/events\/e1\/photos\/p2\/thumbnail$/),
      headers: { Authorization: 'Bearer jwt' },
    });
  });

  it('donne accès à l’ajout de photo et à la modification', async () => {
    api(concert, []);
    await openConcert();
    await screen.findByTestId('photos-empty');

    expect(screen.getByTestId('take-photo')).toBeEnabled();
    await fireEvent.press(screen.getByTestId('detail-edit'));
    expect(await screen.findByTestId('event-title')).toHaveDisplayValue('Concert');
  });

  it('rafraîchit le temps restant chaque minute', async () => {
    jest.useFakeTimers({ now: new Date('2099-12-24T19:00:30Z'), doNotFake: ['nextTick', 'setImmediate'] });
    api(concert, []);
    await openConcert();
    expect(screen.getByTestId('detail-remaining')).toHaveTextContent('dans 29 minutes');

    await act(async () => {
      jest.advanceTimersByTime(CLOCK_TICK_MS);
    });
    expect(screen.getByTestId('detail-remaining')).toHaveTextContent('dans 28 minutes');
  });
});
