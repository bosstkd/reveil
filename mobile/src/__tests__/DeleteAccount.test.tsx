import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Alert, AlertButton } from 'react-native';
import App from '../../App';
import { resolveApiUrl } from '../config';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

const store = (SecureStore as unknown as { __store: Map<string, string> }).__store;
const fetchMock = jest.fn();
let alertSpy: jest.SpyInstance;

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const SESSION = JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });

/** API simulée : liste vide ; DELETE /api/account traité par `onDelete`. */
function api(onDelete: (init: RequestInit) => Response) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/events?')) {
      return jsonResponse(200, { when: 'upcoming', items: [], page: 0, size: 100, totalElements: 0, hasNext: false });
    }
    if (url.endsWith('/api/account') && init?.method === 'DELETE') return onDelete(init);
    return jsonResponse(204, null);
  });
}

const accountCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/account'));

function alertButton(text: string): AlertButton {
  const buttons = alertSpy.mock.calls.at(-1)![2] as AlertButton[];
  return buttons.find((b) => b.text === text)!;
}

async function openSettingsAndConfirm(password: string) {
  await render(<App />);
  await fireEvent.press(await screen.findByTestId('settings'));
  await screen.findByTestId('settings-screen');
  await fireEvent.changeText(screen.getByTestId('delete-password'), password);
  await fireEvent.press(screen.getByTestId('delete-account'));
}

beforeEach(() => {
  store.clear();
  store.set('reveil.session', SESSION);
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
});

afterEach(() => alertSpy.mockRestore());

describe('Suppression du compte (mobile)', () => {
  it('ouvre les paramètres depuis la liste et y revient', async () => {
    api(() => jsonResponse(204, null));
    await render(<App />);
    await fireEvent.press(await screen.findByTestId('settings'));
    expect(await screen.findByText(/alice@example.com/)).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('settings-back'));
    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
  });

  it('mot de passe vide : message, sans confirmation ni appel à l’API', async () => {
    api(() => jsonResponse(204, null));
    await openSettingsAndConfirm('');
    expect(await screen.findByTestId('delete-error')).toHaveTextContent('Saisissez votre mot de passe pour confirmer.');
    expect(alertSpy).not.toHaveBeenCalled();
    expect(accountCalls()).toHaveLength(0);
  });

  it('annuler la confirmation ne supprime rien', async () => {
    api(() => jsonResponse(204, null));
    await openSettingsAndConfirm('secret123');
    expect(alertSpy).toHaveBeenCalledWith('Supprimer mon compte ?', expect.any(String), expect.any(Array));
    expect(alertButton('Annuler').onPress).toBeUndefined();
    expect(accountCalls()).toHaveLength(0);
  });

  it('confirme par mot de passe, supprime le compte et revient à la connexion', async () => {
    api(() => jsonResponse(204, null));
    await openSettingsAndConfirm('secret123');
    await act(async () => alertButton('Supprimer').onPress!());

    expect(await screen.findByTestId('login-email')).toBeOnTheScreen();
    const [[url, init]] = accountCalls();
    expect(url).toMatch(/\/api\/account$/);
    expect(init.method).toBe('DELETE');
    expect(JSON.parse(init.body)).toEqual({ password: 'secret123' });
    expect(init.headers.Authorization).toBe('Bearer jwt');
    await waitFor(() => expect(store.has('reveil.session')).toBe(false));
    // Les sessions sont révoquées côté serveur : pas d'appel de déconnexion.
    expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/api/auth/logout'))).toBe(false);
  });

  it('mot de passe refusé (403) : message, session conservée', async () => {
    api(() => jsonResponse(403, { message: 'Mot de passe incorrect', errors: [{ field: 'password', message: 'Mot de passe incorrect' }] }));
    await openSettingsAndConfirm('mauvais1');
    // Le rafraîchissement au démarrage (session restaurée) est hors sujet : seul compte ce qui suit la suppression.
    const refreshes = () => fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/api/auth/refresh')).length;
    const before = refreshes();
    await act(async () => alertButton('Supprimer').onPress!());

    expect(await screen.findByTestId('delete-error')).toHaveTextContent('Mot de passe incorrect');
    expect(screen.getByTestId('delete-password').props.value).toBe('');
    expect(store.get('reveil.session')).toBe(SESSION);
    expect(refreshes()).toBe(before);
  });

  it('serveur injoignable : message, session conservée', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/api/account') && init?.method === 'DELETE') throw new TypeError('Network request failed');
      if (String(url).includes('/api/events?')) {
        return jsonResponse(200, { when: 'upcoming', items: [], page: 0, size: 100, totalElements: 0, hasNext: false });
      }
      return jsonResponse(204, null);
    });
    await openSettingsAndConfirm('secret123');
    await act(async () => alertButton('Supprimer').onPress!());
    expect(await screen.findByTestId('delete-error')).toHaveTextContent(/Serveur injoignable/);
    expect(store.get('reveil.session')).toBe(SESSION);
  });
});

describe('URL de l’API (HTTPS)', () => {
  it('exige HTTPS hors développement', () => {
    expect(() => resolveApiUrl('http://api.example.com', false)).toThrow(/HTTPS/);
    expect(() => resolveApiUrl(undefined, false)).toThrow(/HTTPS/);
    expect(resolveApiUrl('https://api.example.com/test/reveil/', false)).toBe('https://api.example.com/test/reveil');
  });

  it('accepte HTTP en développement (serveur local)', () => {
    expect(resolveApiUrl('http://192.168.1.10:8081', true)).toBe('http://192.168.1.10:8081');
  });
});
