import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
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

async function fillAndSubmit(email: string, password: string) {
  await fireEvent.press(await screen.findByTestId('to-register'));
  await screen.findByText('Créer un compte');
  await fireEvent.changeText(screen.getByTestId('email'), email);
  await fireEvent.changeText(screen.getByTestId('password'), password);
  await fireEvent.press(screen.getByTestId('submit'));
}

beforeEach(() => {
  store.clear();
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('Inscription (mobile)', () => {
  it('refuse un e-mail invalide sans appeler l’API', async () => {
    await render(<App />);
    await fillAndSubmit('alice@example', 'motdepasse1');
    expect(await screen.findByTestId('email-error')).toHaveTextContent('Saisissez un e-mail valide.');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuse un mot de passe trop court ou sans chiffre', async () => {
    await render(<App />);
    await fillAndSubmit('alice@example.com', 'abc1234');
    expect(await screen.findByTestId('password-error')).toHaveTextContent(/au moins 8 caractères/);

    await fireEvent.changeText(screen.getByTestId('password'), 'sanschiffre');
    await fireEvent.press(screen.getByTestId('submit'));
    expect(await screen.findByTestId('password-error')).toHaveTextContent(/1 chiffre/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('connecte et affiche la liste des événements après inscription', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(201, { token: 'jwt', expiresIn: 900, refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } }),
    );
    await render(<App />);
    await fillAndSubmit(' alice@example.com ', 'abcdefg1');

    expect(await screen.findByText('Mes événements')).toBeOnTheScreen();
    expect(screen.getByText(/alice@example.com/)).toBeOnTheScreen();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/register$/);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ email: 'alice@example.com', password: 'abcdefg1' });

    const saved = store.get('reveil.session') ?? '';
    expect(saved).toContain('jwt');
    expect(saved).not.toContain('abcdefg1');
  });

  it('affiche « Un compte existe déjà avec cet e-mail » sur 409', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { message: 'Un compte existe déjà avec cet e-mail' }));
    await render(<App />);
    await fillAndSubmit('alice@example.com', 'motdepasse1');

    expect(await screen.findByTestId('server-error')).toHaveTextContent('Un compte existe déjà avec cet e-mail');
    expect(screen.queryByText('Mes événements')).toBeNull();
  });

  it('affiche une erreur si le serveur est injoignable', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'));
    await render(<App />);
    await fillAndSubmit('alice@example.com', 'motdepasse1');
    expect(await screen.findByTestId('server-error')).toHaveTextContent(/injoignable/);
  });

  it('revient à la connexion depuis l’inscription', async () => {
    await render(<App />);
    await fireEvent.press(await screen.findByTestId('to-register'));
    await fireEvent.press(await screen.findByTestId('to-login'));
    expect(await screen.findByTestId('login-submit')).toBeOnTheScreen();
  });
});
