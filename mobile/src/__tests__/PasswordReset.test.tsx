import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Linking } from 'react-native';
import App, { PASSWORD_CHANGED_NOTICE } from '../../App';
import { INVALID_RESET_LINK_MESSAGE, RESET_REQUESTED_MESSAGE } from '../auth/authApi';
import { extractResetToken } from '../auth/resetLink';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

const fetchMock = jest.fn();
const TOKEN = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde';
const urlListeners = new Set<(e: { url: string }) => void>();

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

function callsTo(path: string) {
  return fetchMock.mock.calls.filter(([url]) => String(url).endsWith(path));
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  urlListeners.clear();
  jest.spyOn(Linking, 'getInitialURL').mockResolvedValue(null);
  jest.spyOn(Linking, 'addEventListener').mockImplementation(((type: string, listener: (e: { url: string }) => void) => {
    if (type === 'url') urlListeners.add(listener);
    return { remove: () => urlListeners.delete(listener) };
  }) as unknown as typeof Linking.addEventListener);
});

afterEach(() => jest.restoreAllMocks());

async function fillNewPassword(password: string, confirm = password) {
  await fireEvent.changeText(screen.getByTestId('reset-password'), password);
  await fireEvent.changeText(screen.getByTestId('reset-confirm'), confirm);
  await fireEvent.press(screen.getByTestId('reset-submit'));
}

describe('extractResetToken', () => {
  it('lit le jeton d’un lien web, d’un lien mobile ou collé seul', () => {
    expect(extractResetToken(`https://h/test/reveil/reinitialiser-mot-de-passe?token=${TOKEN}`)).toBe(TOKEN);
    expect(extractResetToken(` reveil://reinitialiser-mot-de-passe?token=${TOKEN} `)).toBe(TOKEN);
    expect(extractResetToken(TOKEN)).toBe(TOKEN);
    expect(extractResetToken('n’importe quoi')).toBeNull();
    expect(extractResetToken('')).toBeNull();
  });
});

describe('Mot de passe oublié (mobile)', () => {
  it('demande : même confirmation que l’e-mail existe ou non', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202, { message: RESET_REQUESTED_MESSAGE }));
    await render(<App />);
    await fireEvent.press(await screen.findByTestId('to-forgot-password'));
    expect(await screen.findByRole('header', { name: 'Mot de passe oublié' })).toBeOnTheScreen();

    await fireEvent.changeText(screen.getByTestId('forgot-email'), ' personne@example.com ');
    await fireEvent.press(screen.getByTestId('forgot-submit'));

    expect(await screen.findByTestId('reset-requested')).toHaveTextContent(RESET_REQUESTED_MESSAGE);
    const [[, init]] = callsTo('/api/auth/password-reset/request');
    expect(JSON.parse(init.body)).toEqual({ email: 'personne@example.com' });
    expect(init.headers.Authorization).toBeUndefined();
  });

  it('demande : e-mail invalide refusé sans appel réseau', async () => {
    await render(<App />);
    await fireEvent.press(await screen.findByTestId('to-forgot-password'));
    await fireEvent.changeText(screen.getByTestId('forgot-email'), 'pas-un-email');
    await fireEvent.press(screen.getByTestId('forgot-submit'));
    expect(await screen.findByTestId('forgot-email-error')).toHaveTextContent('Saisissez un e-mail valide.');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('lien collé : nouveau mot de passe enregistré puis retour à la connexion avec confirmation', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/request') ? jsonResponse(202, { message: RESET_REQUESTED_MESSAGE }) : jsonResponse(204, null),
    );
    await render(<App />);
    await fireEvent.press(await screen.findByTestId('to-forgot-password'));
    await fireEvent.changeText(screen.getByTestId('forgot-email'), 'alice@example.com');
    await fireEvent.press(screen.getByTestId('forgot-submit'));
    await fireEvent.press(await screen.findByTestId('to-reset-password'));

    // Sans lien : erreur de saisie, aucun appel.
    await fillNewPassword('nouveau456');
    expect(await screen.findByTestId('reset-link-error')).toBeOnTheScreen();
    expect(callsTo('/api/auth/password-reset/confirm')).toHaveLength(0);

    await fireEvent.changeText(
      screen.getByTestId('reset-link'),
      `https://h/test/reveil/reinitialiser-mot-de-passe?token=${TOKEN}`,
    );
    await fillNewPassword('nouveau456');

    expect(await screen.findByTestId('login-notice')).toHaveTextContent(PASSWORD_CHANGED_NOTICE);
    const [[, init]] = callsTo('/api/auth/password-reset/confirm');
    expect(JSON.parse(init.body)).toEqual({ token: TOKEN, password: 'nouveau456' });
  });

  it('lien profond reveil:// à l’ouverture : écran du nouveau mot de passe sans saisie du lien', async () => {
    (Linking.getInitialURL as jest.Mock).mockResolvedValue(`reveil://reinitialiser-mot-de-passe?token=${TOKEN}`);
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    await render(<App />);

    expect(await screen.findByRole('header', { name: 'Nouveau mot de passe' })).toBeOnTheScreen();
    expect(screen.queryByTestId('reset-link')).toBeNull();
    await fillNewPassword('nouveau456');
    expect(await screen.findByTestId('login-notice')).toHaveTextContent(PASSWORD_CHANGED_NOTICE);
    expect(JSON.parse(callsTo('/api/auth/password-reset/confirm')[0][1].body).token).toBe(TOKEN);
  });

  it('lien profond reçu application ouverte ; règles du mot de passe vérifiées localement', async () => {
    await render(<App />);
    await screen.findByTestId('login-submit');
    await waitFor(() => expect(urlListeners.size).toBeGreaterThan(0));
    await act(async () => urlListeners.forEach((l) => l({ url: `reveil://reinitialiser-mot-de-passe?token=${TOKEN}` })));

    expect(await screen.findByTestId('reset-password')).toBeOnTheScreen();
    await fillNewPassword('court');
    expect(await screen.findByTestId('reset-password-error')).toHaveTextContent(/au moins 8 caractères/);
    await fillNewPassword('nouveau456', 'nouveau457');
    expect(await screen.findByTestId('reset-confirm-error')).toHaveTextContent(/ne correspondent pas/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('lien expiré ou déjà utilisé : message et nouvelle demande proposée', async () => {
    (Linking.getInitialURL as jest.Mock).mockResolvedValue(`reveil://reinitialiser-mot-de-passe?token=${TOKEN}`);
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        message: INVALID_RESET_LINK_MESSAGE,
        errors: [{ field: 'token', message: INVALID_RESET_LINK_MESSAGE }],
      }),
    );
    await render(<App />);
    await screen.findByTestId('reset-password');
    await fillNewPassword('nouveau456');

    expect(await screen.findByTestId('reset-invalid-link')).toHaveTextContent(INVALID_RESET_LINK_MESSAGE);
    await fireEvent.press(screen.getByTestId('reset-request-new'));
    expect(await screen.findByTestId('forgot-email')).toBeOnTheScreen();
  });
});
