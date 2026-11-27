import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import App from '../../App';

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

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const session = (token: string, refreshToken: string) => ({
  token,
  expiresIn: 900,
  refreshToken,
  user: { id: 'u1', email: 'alice@example.com' },
});

async function login(email: string, password: string) {
  await fireEvent.changeText(await screen.findByTestId('login-email'), email);
  await fireEvent.changeText(screen.getByTestId('login-password'), password);
  await fireEvent.press(screen.getByTestId('login-submit'));
}

beforeEach(() => {
  store.clear();
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('Connexion (mobile)', () => {
  it('affiche l’écran de connexion par défaut', async () => {
    await render(<App />);
    expect(await screen.findByRole('header', { name: 'Se connecter' })).toBeOnTheScreen();
    expect(screen.getByTestId('login-submit')).toBeOnTheScreen();
  });

  it('identifiants valides : ouvre la session, stocke les jetons dans le trousseau sécurisé', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, session('jwt', 's.r1')));
    await render(<App />);
    await login(' alice@example.com ', 'motdepasse1');

    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/login$/);
    expect(JSON.parse(init.body)).toEqual({ email: 'alice@example.com', password: 'motdepasse1' });

    expect(SecureStore.setItemAsync).toHaveBeenCalled();
    const saved = JSON.parse(store.get('reveil.session') ?? '{}');
    expect(saved).toEqual({ token: 'jwt', refreshToken: 's.r1', user: { id: 'u1', email: 'alice@example.com' } });
    expect(JSON.stringify(saved)).not.toContain('motdepasse1');
  });

  it('identifiants invalides : message générique sans préciser le champ', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { message: 'E-mail ou mot de passe incorrect', errors: [] }));
    await render(<App />);
    await login('alice@example.com', 'mauvais');

    expect(await screen.findByTestId('login-error')).toHaveTextContent('E-mail ou mot de passe incorrect');
    expect(screen.queryByTestId('email-error')).toBeNull();
    expect(screen.queryByTestId('password-error')).toBeNull();
    expect(screen.getByTestId('login-password').props.value).toBe('');
    expect(store.has('reveil.session')).toBe(false);
  });

  it('champs vides : même message générique, sans appel réseau', async () => {
    await render(<App />);
    await login('', '');
    expect(await screen.findByTestId('login-error')).toHaveTextContent('E-mail ou mot de passe incorrect');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('serveur injoignable : message explicite', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'));
    await render(<App />);
    await login('alice@example.com', 'motdepasse1');
    expect(await screen.findByTestId('login-error')).toHaveTextContent(/injoignable/);
  });
});

describe('Session persistante (mobile)', () => {
  it('au redémarrage : session restaurée sans saisie et prolongée par rafraîchissement', async () => {
    store.set('reveil.session', JSON.stringify(session('old', 's.r1')));
    fetchMock.mockResolvedValue(jsonResponse(200, session('new', 's.r2')));

    await render(<App />);
    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
    expect(screen.queryByTestId('login-submit')).toBeNull();

    await waitFor(() => expect(JSON.parse(store.get('reveil.session')!).refreshToken).toBe('s.r2'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/refresh$/);
    expect(JSON.parse(init.body)).toEqual({ refreshToken: 's.r1' });
  });

  it('hors ligne au redémarrage : la session est conservée', async () => {
    store.set('reveil.session', JSON.stringify(session('old', 's.r1')));
    fetchMock.mockRejectedValue(new TypeError('Network request failed'));

    await render(<App />);
    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(store.has('reveil.session')).toBe(true);
  });

  it('session expirée ou révoquée (401) : retour à l’écran de connexion', async () => {
    store.set('reveil.session', JSON.stringify(session('old', 's.r1')));
    fetchMock.mockResolvedValue(jsonResponse(401, { message: 'Session expirée' }));

    await render(<App />);
    expect(await screen.findByTestId('login-submit')).toBeOnTheScreen();
    expect(store.has('reveil.session')).toBe(false);
  });

  it('ignore une session stockée incomplète (ancien format sans jeton de rafraîchissement)', async () => {
    store.set('reveil.session', JSON.stringify({ token: 't', user: { id: '1', email: 'bob@example.com' } }));
    await render(<App />);
    expect(await screen.findByTestId('login-submit')).toBeOnTheScreen();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('Déconnexion (mobile)', () => {
  it('« Se déconnecter » révoque la session de cet appareil et efface les jetons', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, session('jwt', 's.r1')));
    await render(<App />);
    await login('alice@example.com', 'motdepasse1');
    await screen.findByText('Mes événements');

    fetchMock.mockResolvedValueOnce(jsonResponse(204, null));
    await act(async () => {
      await fireEvent.press(screen.getByTestId('logout'));
    });

    expect(await screen.findByTestId('login-submit')).toBeOnTheScreen();
    expect(store.has('reveil.session')).toBe(false);
    // L'écran des événements charge aussi la liste (GET /api/events) : on cible l'appel de déconnexion.
    const [url, init] = fetchMock.mock.calls.find(([u]) => /\/api\/auth\/logout$/.test(u))!;
    expect(url).toMatch(/\/api\/auth\/logout$/);
    expect(JSON.parse(init.body)).toEqual({ refreshToken: 's.r1' });
    expect(init.headers.Authorization).toBe('Bearer jwt');
  });
});
