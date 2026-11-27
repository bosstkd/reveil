import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Alert, AlertButton, Dimensions } from 'react-native';
import App from '../../App';
import { API_URL } from '../config';
import { DELETE_FAILED } from '../photos/photosApi';
import { DELETE_PHOTO_TITLE } from '../screens/PhotoViewer';

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

type Deleter = (url: string) => Promise<Response> | Response;

/** API simulée : un événement, sa galerie, suppression traitée par `onDelete`. */
function api(gallery: object[], onDelete: Deleter = () => jsonResponse(204, null)) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [concert], page: 0, size: 100, totalElements: 1, hasNext: false });
    }
    if (init?.method === 'DELETE') return onDelete(url);
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, concert);
    if (url.endsWith('/api/events/e1/photos')) return jsonResponse(200, gallery);
    return jsonResponse(204, null);
  });
}

const deletes = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => url as string);

async function openConcert() {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('event-item'));
  await screen.findByTestId('detail-title');
  await screen.findByTestId('gallery');
}

const counter = () => screen.getByTestId('viewer-counter');
const viewerImages = () => within(screen.getByTestId('photo-viewer')).queryAllByTestId('viewer-image');

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

describe('Visionneuse plein écran', () => {
  it('un appui sur une miniature ouvre la photo d’origine en plein écran, avec le jeton', async () => {
    api([photo('p3'), photo('p2'), photo('p1')]);
    await openConcert();
    expect(screen.queryByTestId('photo-viewer')).toBeNull();

    await fireEvent.press(screen.getAllByTestId('gallery-open')[1]);

    expect(screen.getByTestId('photo-viewer')).toBeOnTheScreen();
    expect(counter()).toHaveTextContent('2 / 3');
    const sources = viewerImages().map((i) => i.props.source);
    expect(sources).toContainEqual({ uri: `${API_URL}/api/events/e1/photos/p2/content`, headers: { Authorization: 'Bearer jwt' } });
    // Virtualisée : la galerie entière n'est pas chargée en pleine taille.
    expect(sources.length).toBeLessThan(3);
  });

  it('défilement vers les suivantes (glissé ou boutons), bornes désactivées', async () => {
    api([photo('p3'), photo('p2'), photo('p1')]);
    await openConcert();
    await fireEvent.press(screen.getAllByTestId('gallery-open')[0]);
    expect(screen.getByTestId('viewer-previous')).toBeDisabled();

    const { width } = Dimensions.get('window');
    await act(async () => {
      fireEvent(screen.getByTestId('viewer-list'), 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: width, y: 0 } } });
    });
    expect(counter()).toHaveTextContent('2 / 3');

    await fireEvent.press(screen.getByTestId('viewer-next'));
    expect(counter()).toHaveTextContent('3 / 3');
    expect(screen.getByTestId('viewer-next')).toBeDisabled();

    await fireEvent.press(screen.getByTestId('viewer-previous'));
    expect(counter()).toHaveTextContent('2 / 3');
  });

  it('✕ ferme la visionneuse', async () => {
    api([photo('p1')]);
    await openConcert();
    await fireEvent.press(screen.getAllByTestId('gallery-open')[0]);
    await fireEvent.press(screen.getByTestId('viewer-close'));
    expect(screen.queryByTestId('photo-viewer')).toBeNull();
  });

  it('la suppression demande une confirmation, puis retire la photo de la galerie', async () => {
    api([photo('p3'), photo('p2'), photo('p1')]);
    await openConcert();
    await fireEvent.press(screen.getAllByTestId('gallery-open')[1]);

    await fireEvent.press(screen.getByTestId('viewer-delete'));
    expect(alertSpy).toHaveBeenCalledWith(DELETE_PHOTO_TITLE, expect.any(String), expect.any(Array));
    // Annuler : rien n'est envoyé.
    expect(alertButton(alertSpy, 'Annuler').style).toBe('cancel');
    expect(deletes()).toEqual([]);

    await fireEvent.press(screen.getByTestId('viewer-delete'));
    await act(async () => {
      alertButton(alertSpy, 'Supprimer').onPress!();
    });

    expect(deletes()).toEqual([`${API_URL}/api/events/e1/photos/p2`]);
    await waitFor(() => expect(counter()).toHaveTextContent('2 / 2'));
    expect(screen.getAllByTestId('gallery-open')).toHaveLength(2);
    expect(screen.getByTestId('detail-notice')).toHaveTextContent('Photo supprimée.');
    // Galerie mise à jour sans rechargement.
    expect(fetchMock.mock.calls.filter(([url]) => (url as string).endsWith('/photos'))).toHaveLength(1);
  });

  it('suppression de la dernière photo restante : la visionneuse se ferme (déjà supprimée ailleurs : idem)', async () => {
    api([photo('p1')], () => jsonResponse(404, { message: 'Photo introuvable', errors: [] }));
    await openConcert();
    await fireEvent.press(screen.getAllByTestId('gallery-open')[0]);
    await fireEvent.press(screen.getByTestId('viewer-delete'));
    await act(async () => {
      alertButton(alertSpy, 'Supprimer').onPress!();
    });
    await waitFor(() => expect(screen.queryByTestId('photo-viewer')).toBeNull());
    expect(screen.getByTestId('photos-empty')).toBeOnTheScreen();
  });

  it('échec de la suppression : message, la photo reste', async () => {
    api([photo('p1')], () => jsonResponse(500, null));
    await openConcert();
    await fireEvent.press(screen.getAllByTestId('gallery-open')[0]);
    await fireEvent.press(screen.getByTestId('viewer-delete'));
    await act(async () => {
      alertButton(alertSpy, 'Supprimer').onPress!();
    });
    expect(await screen.findByTestId('viewer-error')).toHaveTextContent(DELETE_FAILED);
    expect(screen.getByTestId('photo-viewer')).toBeOnTheScreen();
    expect(screen.getAllByTestId('gallery-open')).toHaveLength(1);
  });

  it('galerie de 50 photos : miniatures seulement (aucune photo d’origine chargée)', async () => {
    api(Array.from({ length: 50 }, (_, i) => photo(`p${50 - i}`)));
    await openConcert();
    const thumbs = screen.getAllByTestId('gallery-photo');
    expect(thumbs).toHaveLength(50);
    expect(thumbs.every((t) => (t.props.source.uri as string).endsWith('/thumbnail'))).toBe(true);
  });
});
