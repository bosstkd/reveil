import { render, waitFor } from '@testing-library/react-native';
import { useEffect } from 'react';
import { AuthProvider, useAuth } from '../auth/AuthContext';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>([
    [
      'reveil.session',
      JSON.stringify({ token: 'old', refreshToken: 's.r1', user: { id: 'u1', email: 'a@b.co' } }),
    ],
  ]);
  return {
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

const res = (status: number, body: unknown = {}) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response;

it('authFetch ajoute le Bearer et, sur 401, renouvelle le jeton une fois puis rejoue', async () => {
  const fetchMock = jest.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/api/auth/refresh')) {
      return res(200, { token: 'new', expiresIn: 900, refreshToken: 's.r2', user: { id: 'u1', email: 'a@b.co' } });
    }
    const auth = (init.headers as Record<string, string>).Authorization;
    return auth === 'Bearer new' ? res(200, ['ok']) : res(401);
  });
  globalThis.fetch = fetchMock as unknown as typeof fetch;

  let status = 0;
  function Probe() {
    const { ready, session, authFetch } = useAuth();
    useEffect(() => {
      if (ready && session) authFetch('/api/events').then((r) => (status = r.status));
    }, [ready]); // eslint-disable-line react-hooks/exhaustive-deps
    return null;
  }

  await render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );

  await waitFor(() => expect(status).toBe(200));
  const eventCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/api/events'));
  expect(eventCalls[eventCalls.length - 1][1].headers).toMatchObject({ Authorization: 'Bearer new' });
  // Un seul rafraîchissement malgré le rafraîchissement de démarrage et le 401 simultanés.
  expect(fetchMock.mock.calls.filter(([u]) => u.endsWith('/api/auth/refresh'))).toHaveLength(1);
});
