import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import * as Manipulator from 'expo-image-manipulator';
import * as Picker from 'expo-image-picker';
import App from '../../App';
import { MAX_GALLERY_SELECTION, SELECTION_TRUNCATED, jpegName, rejectReason } from '../photos/photos';
import { BATCH_UPLOAD_FAILED } from '../photos/photosApi';
import { GALLERY_FAILED, PREPARE_FAILED } from '../screens/EventDetailScreen';

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
const picker = Picker as unknown as {
  getCameraPermissionsAsync: jest.Mock;
  requestCameraPermissionsAsync: jest.Mock;
  launchCameraAsync: jest.Mock;
  launchImageLibraryAsync: jest.Mock;
};
const manipulator = Manipulator as unknown as {
  ImageManipulator: { manipulate: jest.Mock };
  __context: { resize: jest.Mock; renderAsync: jest.Mock };
  __saveAsync: jest.Mock;
};
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

type Upload = (init: RequestInit) => Promise<Response> | Response;

/** API simulée : un événement, galerie initiale `gallery`, envoi multiple traité par `upload`. */
function api(gallery: object[], upload: Upload) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [concert], page: 0, size: 100, totalElements: 1, hasNext: false });
    }
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, concert);
    if (url.endsWith('/api/events/e1/photos/batch') && init?.method === 'POST') return upload(init);
    if (url.endsWith('/api/events/e1/photos')) return jsonResponse(200, gallery);
    return jsonResponse(204, null);
  });
}

const uploads = () => fetchMock.mock.calls.filter(([url, init]) => url.endsWith('/photos/batch') && init?.method === 'POST');
type Part = { fieldName: string; uri?: string; type?: string; name?: string };
/** FormData de React Native (fichiers { uri, name, type }) : celui de Node ne les accepte pas. */
class RNFormData {
  private readonly parts: Part[] = [];
  append(fieldName: string, value: object) {
    this.parts.push({ fieldName, ...value });
  }
  getParts() {
    return this.parts;
  }
}
globalThis.FormData = RNFormData as unknown as typeof FormData;
const parts = (init: RequestInit) => (init.body as unknown as { getParts: () => Part[] }).getParts();

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
  picker.getCameraPermissionsAsync.mockReset().mockResolvedValue({ granted: false, canAskAgain: true, status: 'undetermined' });
  picker.requestCameraPermissionsAsync.mockReset().mockResolvedValue({ granted: true, canAskAgain: true, status: 'granted' });
  picker.launchCameraAsync.mockClear();
  picker.launchImageLibraryAsync.mockReset().mockResolvedValue({ canceled: true, assets: null });
  manipulator.__context.resize.mockClear();
  manipulator.__saveAsync.mockClear();
  manipulator.ImageManipulator.manipulate.mockClear();
});


/** Photos choisies dans la galerie du système. */
const asset = (name: string, mimeType = 'image/jpeg', extra: object = {}) => ({
  uri: `file:///library/${name}`,
  width: 4032,
  height: 3024,
  fileName: name,
  mimeType,
  fileSize: 3_000_000,
  ...extra,
});
const choose = (...assets: object[]) => picker.launchImageLibraryAsync.mockResolvedValueOnce({ canceled: false, assets });
const errors = () => screen.queryAllByTestId('import-error').map((e) => e.props.children as string);

/** Réponse de l'API : chaque fichier reçu devient une photo `p<i>`. */
const allSaved: Upload = (init) =>
  jsonResponse(200, { photos: parts(init).map((_, i) => photo(`p${i + 1}`)), rejected: [] });

describe('Règles d’import', () => {
  it('JPEG, PNG et HEIC acceptés ; autres formats refusés avec un message explicite', () => {
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'a.jpg', mimeType: 'image/jpeg' }, 0)).toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'b.png', mimeType: 'image/png' }, 0)).toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'IMG_1.HEIC', mimeType: 'image/heic' }, 0)).toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'IMG_1.HEIC' }, 0)).toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1 }, 0)).toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'anim.gif', mimeType: 'image/gif' }, 0)).toBe(
      '« anim.gif » : format non pris en charge (JPEG, PNG ou HEIC uniquement).',
    );
    expect(rejectReason({ uri: 'u', width: 1, height: 1, name: 'a.webp' }, 0)).not.toBeNull();
    expect(rejectReason({ uri: 'u', width: 1, height: 1, mimeType: 'image/webp' }, 2)).toContain('« photo 3 »');
  });

  it('nom du fichier envoyé : JPEG, nom d’origine conservé', () => {
    expect(jpegName({ uri: 'u', width: 1, height: 1, name: 'IMG_0001.HEIC' }, 0)).toBe('IMG_0001.jpg');
    expect(jpegName({ uri: 'u', width: 1, height: 1 }, 4)).toBe('photo-5.jpg');
  });
});

describe('Import depuis la galerie', () => {
  it('ouvre le sélecteur multiple limité à 10 photos, sans demander d’autorisation', async () => {
    api([], allSaved);
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    await waitFor(() => expect(picker.launchImageLibraryAsync).toHaveBeenCalled());
    expect(picker.launchImageLibraryAsync).toHaveBeenCalledWith(
      expect.objectContaining({ mediaTypes: ['images'], allowsMultipleSelection: true, selectionLimit: 10 }),
    );
    expect(MAX_GALLERY_SELECTION).toBe(10);
    expect(picker.getCameraPermissionsAsync).not.toHaveBeenCalled();
    // Annulation : rien n'est envoyé, le bouton redevient disponible.
    await waitFor(() => expect(screen.getByTestId('pick-photos')).toBeEnabled());
    expect(uploads()).toHaveLength(0);
  });

  it('10 photos choisies : compressées puis envoyées en un seul envoi, et ajoutées en tête de la galerie', async () => {
    api([photo('p0')], allSaved);
    choose(...Array.from({ length: 10 }, (_, i) => asset(`IMG_${i}.jpg`)));
    await openConcert();
    expect(await screen.findAllByTestId('gallery-photo')).toHaveLength(1);

    await fireEvent.press(screen.getByTestId('pick-photos'));
    await waitFor(() => expect(screen.getAllByTestId('gallery-photo')).toHaveLength(11));

    expect(manipulator.ImageManipulator.manipulate).toHaveBeenCalledTimes(10);
    expect(manipulator.ImageManipulator.manipulate).toHaveBeenCalledWith('file:///library/IMG_0.jpg');
    expect(manipulator.__context.resize).toHaveBeenCalledWith({ width: 2048 });
    expect(uploads()).toHaveLength(1);
    const [url, init] = uploads()[0];
    expect(url).toMatch(/\/api\/events\/e1\/photos\/batch$/);
    expect(init.headers.Authorization).toBe('Bearer jwt');
    expect(parts(init)).toHaveLength(10);
    expect(parts(init)[0]).toEqual({
      fieldName: 'files',
      uri: 'file:///cache/compressed.jpg',
      name: 'IMG_0.jpg',
      type: 'image/jpeg',
    });
    // Dernière photo du lot en premier (la plus récente), puis la galerie existante.
    const sources = screen.getAllByTestId('gallery-photo').map((i) => i.props.source.uri as string);
    expect(sources[0]).toMatch(/\/photos\/p10\/thumbnail$/);
    expect(sources[10]).toMatch(/\/photos\/p0\/thumbnail$/);
    expect(screen.queryByTestId('import-errors')).toBeNull();
  });

  it('plus de 10 photos rendues par le système : seules les 10 premières sont importées, avec un message', async () => {
    api([], allSaved);
    choose(...Array.from({ length: 12 }, (_, i) => asset(`IMG_${i}.jpg`)));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    await waitFor(() => expect(uploads()).toHaveLength(1));
    expect(parts(uploads()[0][1])).toHaveLength(10);
    expect(await screen.findByText(SELECTION_TRUNCATED)).toBeOnTheScreen();
  });

  it('photo d’un autre format : refusée avec un message, les autres sont envoyées', async () => {
    api([], allSaved);
    choose(asset('a.jpg'), asset('anim.gif', 'image/gif'), asset('IMG_7.HEIC', 'image/heic'), asset('b.png', 'image/png'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    await waitFor(() => expect(screen.getAllByTestId('gallery-photo')).toHaveLength(3));

    expect(errors()).toEqual(['« anim.gif » : format non pris en charge (JPEG, PNG ou HEIC uniquement).']);
    expect(parts(uploads()[0][1]).map((p) => p.name)).toEqual(['a.jpg', 'IMG_7.jpg', 'b.jpg']);
  });

  it('aucune photo acceptable : aucun envoi', async () => {
    api([], allSaved);
    choose(asset('anim.gif', 'image/gif'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    expect(await screen.findByTestId('import-errors')).toBeOnTheScreen();
    await waitFor(() => expect(screen.getByTestId('pick-photos')).toBeEnabled());
    expect(uploads()).toHaveLength(0);
  });

  it('photo impossible à préparer : message pour elle seule', async () => {
    api([], allSaved);
    choose(asset('a.jpg'), asset('b.jpg'));
    manipulator.__context.renderAsync.mockRejectedValueOnce(new Error('décodage'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    await waitFor(() => expect(uploads()).toHaveLength(1));
    expect(parts(uploads()[0][1]).map((p) => p.name)).toEqual(['b.jpg']);
    expect(await screen.findByText(`« a.jpg » : ${PREPARE_FAILED}`)).toBeOnTheScreen();
  });

  it('photos refusées par l’API (taille, format) : message explicite par photo', async () => {
    api([], () =>
      jsonResponse(200, {
        photos: [photo('p1')],
        rejected: [{ index: 1, filename: 'b.jpg', status: 413, message: 'Photo trop volumineuse (maximum 15 Mo)' }],
      }),
    );
    choose(asset('a.jpg'), asset('b.jpg'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    expect(await screen.findByText('« b.jpg » : Photo trop volumineuse (maximum 15 Mo)')).toBeOnTheScreen();
    expect(screen.getAllByTestId('gallery-photo')).toHaveLength(1);
  });

  it('échec réseau : « Réessayer » renvoie le même lot sans rouvrir la galerie', async () => {
    let attempt = 0;
    api([], (init) => {
      attempt += 1;
      if (attempt === 1) throw new TypeError('Network request failed');
      return allSaved(init);
    });
    choose(asset('a.jpg'), asset('b.jpg'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    expect(await screen.findByTestId('upload-error-message')).toHaveTextContent(BATCH_UPLOAD_FAILED);

    await fireEvent.press(screen.getByTestId('retry-upload'));
    await waitFor(() => expect(screen.getAllByTestId('gallery-photo')).toHaveLength(2));
    expect(uploads()).toHaveLength(2);
    expect(parts(uploads()[1][1])).toEqual(parts(uploads()[0][1]));
    expect(picker.launchImageLibraryAsync).toHaveBeenCalledTimes(1);
    expect(manipulator.ImageManipulator.manipulate).toHaveBeenCalledTimes(2);
    expect(screen.queryByTestId('upload-error')).toBeNull();
  });

  it('envoi refusé en bloc par l’API : son message est affiché', async () => {
    api([], () => jsonResponse(400, { message: 'Trop de photos : 10 au maximum par envoi', errors: [] }));
    choose(asset('a.jpg'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    expect(await screen.findByTestId('upload-error-message')).toHaveTextContent('Trop de photos : 10 au maximum par envoi');
  });

  it('galerie indisponible : message, rien n’est envoyé', async () => {
    api([], allSaved);
    picker.launchImageLibraryAsync.mockRejectedValueOnce(new Error('indisponible'));
    await openConcert();
    await fireEvent.press(screen.getByTestId('pick-photos'));
    expect(await screen.findByText(GALLERY_FAILED)).toBeOnTheScreen();
    expect(uploads()).toHaveLength(0);
  });
});
