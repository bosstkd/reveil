import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import * as Manipulator from 'expo-image-manipulator';
import * as Picker from 'expo-image-picker';
import App from '../../App';
import { compressPhoto, ensureCameraPermission } from '../photos/camera';
import { MAX_WIDTH, targetWidth } from '../photos/photos';
import { UPLOAD_FAILED } from '../photos/photosApi';
import { CAMERA_BLOCKED, CAMERA_DENIED } from '../screens/EventDetailScreen';

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

/** API simulée : un événement, galerie initiale `gallery`, envoi traité par `upload`. */
function api(gallery: object[], upload: Upload) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [concert], page: 0, size: 100, totalElements: 1, hasNext: false });
    }
    if (url.endsWith('/api/events/e1')) return jsonResponse(200, concert);
    if (url.endsWith('/api/events/e1/photos') && init?.method === 'POST') return upload(init);
    if (url.endsWith('/api/events/e1/photos')) return jsonResponse(200, gallery);
    return jsonResponse(204, null);
  });
}

const uploads = () => fetchMock.mock.calls.filter(([url, init]) => url.endsWith('/photos') && init?.method === 'POST');
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
  manipulator.__context.resize.mockClear();
  manipulator.__saveAsync.mockClear();
  manipulator.ImageManipulator.manipulate.mockClear();
});

describe('Compression avant envoi', () => {
  it('limite la largeur à 2048 px sans jamais agrandir', () => {
    expect(MAX_WIDTH).toBe(2048);
    expect(targetWidth(4032)).toBe(2048);
    expect(targetWidth(2048)).toBeNull();
    expect(targetWidth(1080)).toBeNull();
  });

  it('redimensionne une photo large et la réencode en JPEG compressé', async () => {
    const out = await compressPhoto({ uri: 'file:///camera/p.jpg', width: 4032, height: 3024 });
    expect(manipulator.ImageManipulator.manipulate).toHaveBeenCalledWith('file:///camera/p.jpg');
    expect(manipulator.__context.resize).toHaveBeenCalledWith({ width: 2048 });
    expect(manipulator.__saveAsync).toHaveBeenCalledWith({ compress: 0.8, format: 'jpeg' });
    expect(out).toEqual({ uri: 'file:///cache/compressed.jpg', width: 2048, height: 1536 });
  });

  it('ne redimensionne pas une photo déjà étroite mais la compresse', async () => {
    await compressPhoto({ uri: 'file:///camera/p.jpg', width: 1080, height: 1920 });
    expect(manipulator.__context.resize).not.toHaveBeenCalled();
    expect(manipulator.__saveAsync).toHaveBeenCalledWith({ compress: 0.8, format: 'jpeg' });
  });
});

describe('Autorisation de l’appareil photo', () => {
  it('est demandée au premier usage seulement', async () => {
    expect(await ensureCameraPermission()).toBe('granted');
    expect(picker.requestCameraPermissionsAsync).toHaveBeenCalledTimes(1);

    picker.getCameraPermissionsAsync.mockResolvedValue({ granted: true, canAskAgain: true, status: 'granted' });
    expect(await ensureCameraPermission()).toBe('granted');
    expect(picker.requestCameraPermissionsAsync).toHaveBeenCalledTimes(1);
  });

  it('distingue un refus d’un refus définitif', async () => {
    picker.requestCameraPermissionsAsync.mockResolvedValue({ granted: false, canAskAgain: true, status: 'denied' });
    expect(await ensureCameraPermission()).toBe('denied');
    picker.getCameraPermissionsAsync.mockResolvedValue({ granted: false, canAskAgain: false, status: 'denied' });
    expect(await ensureCameraPermission()).toBe('blocked');
  });

  it('n’est pas demandée à l’ouverture de l’événement, mais à l’appui sur « Prendre une photo »', async () => {
    api([], () => jsonResponse(201, photo('p1')));
    await openConcert();
    await screen.findByTestId('photos-empty');
    expect(picker.requestCameraPermissionsAsync).not.toHaveBeenCalled();

    await fireEvent.press(screen.getByTestId('take-photo'));
    await waitFor(() => expect(picker.requestCameraPermissionsAsync).toHaveBeenCalledTimes(1));
  });

  it('refusée : message, et l’appareil photo ne s’ouvre pas', async () => {
    picker.requestCameraPermissionsAsync.mockResolvedValue({ granted: false, canAskAgain: true, status: 'denied' });
    api([], () => jsonResponse(201, photo('p1')));
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));

    expect(await screen.findByTestId('camera-permission')).toHaveTextContent(CAMERA_DENIED, { exact: false });
    expect(screen.queryByTestId('open-settings')).toBeNull();
    expect(picker.launchCameraAsync).not.toHaveBeenCalled();
  });

  it('refusée définitivement : propose d’ouvrir les réglages', async () => {
    picker.getCameraPermissionsAsync.mockResolvedValue({ granted: false, canAskAgain: false, status: 'denied' });
    api([], () => jsonResponse(201, photo('p1')));
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));

    expect(await screen.findByTestId('camera-permission')).toHaveTextContent(CAMERA_BLOCKED, { exact: false });
    expect(screen.getByTestId('open-settings')).toBeOnTheScreen();
  });
});

describe('Prise et envoi d’une photo', () => {
  it('la photo compressée est envoyée pour l’événement sélectionné et apparaît dans sa galerie', async () => {
    api([photo('p0')], () => jsonResponse(201, photo('p1')));
    await openConcert();
    expect(await screen.findAllByTestId('gallery-photo')).toHaveLength(1);

    await fireEvent.press(screen.getByTestId('take-photo'));
    await waitFor(() => expect(screen.getAllByTestId('gallery-photo')).toHaveLength(2));

    expect(picker.launchCameraAsync).toHaveBeenCalledWith(expect.objectContaining({ mediaTypes: ['images'] }));
    expect(uploads()).toHaveLength(1);
    const [url, init] = uploads()[0];
    expect(url).toMatch(/\/api\/events\/e1\/photos$/);
    expect(init.headers.Authorization).toBe('Bearer jwt');
    expect(parts(init)).toEqual([
      expect.objectContaining({ fieldName: 'file', uri: 'file:///cache/compressed.jpg', type: 'image/jpeg' }),
    ]);
    // La plus récente en premier, miniature servie par l'API avec le jeton.
    const first = screen.getAllByTestId('gallery-photo')[0];
    expect(first.props.source).toEqual({
      uri: expect.stringMatching(/\/api\/events\/e1\/photos\/p1\/thumbnail$/),
      headers: { Authorization: 'Bearer jwt' },
    });
    expect(screen.queryByTestId('upload-error')).toBeNull();
  });

  it('annulation de la prise de vue : rien n’est envoyé', async () => {
    picker.launchCameraAsync.mockResolvedValueOnce({ canceled: true, assets: null });
    api([], () => jsonResponse(201, photo('p1')));
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));
    await waitFor(() => expect(picker.launchCameraAsync).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('take-photo')).toBeEnabled());
    expect(uploads()).toHaveLength(0);
  });

  it('échec réseau : message d’erreur, puis « Réessayer » renvoie la même photo sans reprendre de vue', async () => {
    let attempt = 0;
    api([], () => {
      attempt += 1;
      if (attempt === 1) throw new TypeError('Network request failed');
      return jsonResponse(201, photo('p1'));
    });
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));

    expect(await screen.findByTestId('upload-error-message')).toHaveTextContent(UPLOAD_FAILED);
    expect(screen.getByTestId('photos-empty')).toBeOnTheScreen();

    await fireEvent.press(screen.getByTestId('retry-upload'));
    await waitFor(() => expect(screen.getAllByTestId('gallery-photo')).toHaveLength(1));
    expect(screen.queryByTestId('upload-error')).toBeNull();
    expect(uploads()).toHaveLength(2);
    expect(parts(uploads()[1][1])[0].uri).toBe('file:///cache/compressed.jpg');
    expect(picker.launchCameraAsync).toHaveBeenCalledTimes(1);
  });

  it('erreur serveur (5xx) : message générique et possibilité de réessayer', async () => {
    api([], () => jsonResponse(503, null));
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));
    expect(await screen.findByTestId('upload-error-message')).toHaveTextContent(UPLOAD_FAILED);
    expect(screen.getByTestId('retry-upload')).toBeOnTheScreen();
  });

  it('refus explicite du serveur : son message est affiché', async () => {
    api([], () => jsonResponse(413, { message: 'Photo trop volumineuse (maximum 10 Mo)', errors: [] }));
    await openConcert();
    await fireEvent.press(screen.getByTestId('take-photo'));
    expect(await screen.findByTestId('upload-error-message')).toHaveTextContent('Photo trop volumineuse (maximum 10 Mo)');
  });
});
