import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Alert, AlertButton } from 'react-native';
import App from '../../App';
import { parseLocalDateTime } from '../events/events';
import { EVENT_FORBIDDEN, EVENT_NOT_FOUND } from '../events/eventsApi';
import { DELETE_MESSAGE, DELETE_TITLE } from '../screens/EventDetailScreen';

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
  return { __esModule: true, default: (props: object) => <View {...props} /> };
});

const store = (SecureStore as unknown as { __store: Map<string, string> }).__store;
const fetchMock = jest.fn();

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const SESSION = JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });

const concert = {
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [] as string[],
  reminders: [
    { minutesBefore: 1440, remindAt: '2099-12-23T19:30:00Z', status: 'sent' as const },
    { minutesBefore: 60, remindAt: '2099-12-24T18:30:00Z', status: 'scheduled' as const },
  ],
};

type Handler = (init: RequestInit) => Response;

/** API simulée : un événement ; PUT et DELETE traités par `onPut` / `onDelete`. */
function api({ onPut, onDelete }: { onPut?: Handler; onDelete?: Handler } = {}) {
  let items = [concert];
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items, page: 0, size: 100, totalElements: items.length, hasNext: false });
    }
    if (url.endsWith('/api/events/e1') && init?.method === 'PUT') return onPut!(init);
    if (url.endsWith('/api/events/e1') && init?.method === 'DELETE') {
      const res = onDelete!(init);
      if (res.ok) items = [];
      return res;
    }
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, concert);
    if (url.endsWith('/api/events/e1/photos')) return jsonResponse(200, []);
    return jsonResponse(204, null);
  });
}

/** Appels `method` sur l'événement e1 (hors enregistrement de l'appareil, PUT /api/devices). */
const calls = (method: string) =>
  fetchMock.mock.calls.filter(([url, init]) => /\/api\/events\/e1$/.test(url) && init?.method === method);

async function openConcert() {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('event-item'));
  await screen.findByTestId('detail-title');
}

async function pick(field: 'event-date' | 'event-time', value: Date) {
  await fireEvent.press(screen.getByTestId(field));
  await act(async () => {
    fireEvent(screen.getByTestId('event-picker'), 'change', { type: 'set', nativeEvent: {} }, value);
  });
}

/** Bouton de la boîte de confirmation affichée par Alert.alert. */
function alertButton(spy: jest.SpyInstance, text: string): AlertButton {
  const buttons = spy.mock.calls.at(-1)![2] as AlertButton[];
  return buttons.find((b) => b.text === text)!;
}

let alertSpy: jest.SpyInstance;

beforeEach(() => {
  store.clear();
  store.set('reveil.session', SESSION);
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
});

afterEach(() => alertSpy.mockRestore());

describe('parseLocalDateTime', () => {
  it('reprend la date et l’heure saisies sans conversion de fuseau', () => {
    const { date, time } = parseLocalDateTime('2099-12-24T20:30:00');
    expect([date.getFullYear(), date.getMonth(), date.getDate()]).toEqual([2099, 11, 24]);
    expect([time.getHours(), time.getMinutes()]).toEqual([20, 30]);
  });
});

describe('Modifier un événement', () => {
  it('pré-remplit toutes les informations et envoie un PUT dans le fuseau de saisie', async () => {
    api({
      onPut: (init) => {
        const body = JSON.parse(init.body as string);
        // 31/12/2099 22:00 à Paris = 21:00 UTC.
        return jsonResponse(200, { ...concert, ...body, startsAt: '2099-12-31T21:00:00Z', localDateTime: `${body.date}T${body.time}:00` });
      },
    });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-edit'));

    expect(screen.getByText("Modifier l'événement")).toBeOnTheScreen();
    expect(screen.getByTestId('event-title').props.value).toBe('Concert');
    expect(screen.getByTestId('event-location').props.value).toBe('Olympia');
    expect(screen.getByTestId('event-time')).toHaveTextContent('20:30');
    expect(screen.getByTestId('event-timezone')).toHaveTextContent('Europe/Paris', { exact: false });

    await fireEvent.changeText(screen.getByTestId('event-title'), ' Concert reporté ');
    await fireEvent.changeText(screen.getByTestId('event-location'), ' ');
    await pick('event-date', new Date(2099, 11, 31));
    await pick('event-time', new Date(2099, 0, 1, 22, 0));
    await fireEvent.press(screen.getByTestId('event-submit'));

    await waitFor(() => expect(calls('PUT')).toHaveLength(1));
    expect(JSON.parse(calls('PUT')[0][1].body)).toEqual({
      title: 'Concert reporté',
      date: '2099-12-31',
      time: '22:00',
      timeZone: 'Europe/Paris',
      location: null,
      reminders: [1440, 60],
    });
    expect(await screen.findByTestId('detail-title')).toHaveTextContent('Concert reporté');
    expect(screen.getByTestId('detail-time')).toHaveTextContent('22:00', { exact: false });
    expect(screen.getByTestId('detail-notice')).toHaveTextContent('Événement modifié.');
  });

  it('pré-coche les rappels configurés et enregistre la nouvelle sélection', async () => {
    api({ onPut: (init) => jsonResponse(200, { ...concert, reminders: [] , title: JSON.parse(String(init.body)).title }) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-edit'));
    expect(screen.getByTestId('reminder-1440').props.accessibilityState).toMatchObject({ checked: true });
    expect(screen.getByTestId('reminder-60').props.accessibilityState).toMatchObject({ checked: true });
    expect(screen.getByTestId('reminder-15').props.accessibilityState).toMatchObject({ checked: false });

    await fireEvent.press(screen.getByTestId('reminder-60'));
    await fireEvent.press(screen.getByTestId('reminder-15'));
    await fireEvent.press(screen.getByTestId('event-submit'));

    await waitFor(() => expect(calls('PUT')).toHaveLength(1));
    expect(JSON.parse(calls('PUT')[0][1].body).reminders).toEqual([1440, 15]);
    expect(await screen.findByTestId('reminders-empty')).toBeOnTheScreen();
  });

  it('bloque l’enregistrement si le titre est vidé', async () => {
    api({ onPut: () => jsonResponse(200, concert) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-edit'));
    await fireEvent.changeText(screen.getByTestId('event-title'), '  ');
    await fireEvent.press(screen.getByTestId('event-submit'));

    expect(screen.getByTestId('title-error')).toBeOnTheScreen();
    expect(calls('PUT')).toHaveLength(0);
  });

  it('affiche le refus du serveur (403)', async () => {
    api({ onPut: () => jsonResponse(403, { message: 'Accès refusé', errors: [] }) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-edit'));
    await fireEvent.press(screen.getByTestId('event-submit'));

    expect(await screen.findByTestId('event-error')).toHaveTextContent(EVENT_FORBIDDEN);
  });

  it('« Annuler » revient au détail sans rien envoyer', async () => {
    api();
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-edit'));
    await fireEvent.press(screen.getByTestId('event-cancel'));

    expect(screen.getByTestId('detail-title')).toHaveTextContent('Concert');
    expect(calls('PUT')).toHaveLength(0);
  });
});

describe('Supprimer un événement', () => {
  it('demande une confirmation ; « Annuler » ne supprime rien', async () => {
    api({ onDelete: () => jsonResponse(204, null) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-delete'));

    expect(alertSpy).toHaveBeenCalledWith(DELETE_TITLE, DELETE_MESSAGE, expect.any(Array));
    expect(DELETE_MESSAGE).toContain('rappels');
    expect(alertButton(alertSpy, 'Annuler').style).toBe('cancel');
    expect(calls('DELETE')).toHaveLength(0);
  });

  it('après confirmation, supprime puis revient à la liste rechargée', async () => {
    api({ onDelete: () => jsonResponse(204, null) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-delete'));
    const confirm = alertButton(alertSpy, 'Supprimer');
    expect(confirm.style).toBe('destructive');
    await act(async () => confirm.onPress!());

    expect(calls('DELETE')).toHaveLength(1);
    expect(calls('DELETE')[0][0]).toMatch(/\/api\/events\/e1$/);
    expect(await screen.findByTestId('events-empty')).toBeOnTheScreen();
    expect(screen.queryByTestId('event-item')).toBeNull();
  });

  it('affiche le refus du serveur (403) et reste sur l’événement', async () => {
    api({ onDelete: () => jsonResponse(403, { message: 'Accès refusé', errors: [] }) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-delete'));
    await act(async () => alertButton(alertSpy, 'Supprimer').onPress!());

    expect(await screen.findByTestId('detail-action-error')).toHaveTextContent(EVENT_FORBIDDEN);
    expect(screen.getByTestId('detail-title')).toHaveTextContent('Concert');
  });

  it('événement déjà supprimé ailleurs (404) : message dédié', async () => {
    api({ onDelete: () => jsonResponse(404, { message: 'Événement introuvable', errors: [] }) });
    await openConcert();
    await fireEvent.press(screen.getByTestId('detail-delete'));
    await act(async () => alertButton(alertSpy, 'Supprimer').onPress!());

    expect(await screen.findByTestId('detail-error')).toHaveTextContent(EVENT_NOT_FOUND);
  });
});
