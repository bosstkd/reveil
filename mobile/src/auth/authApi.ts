import { API_URL } from '../config';
import { EMAIL_ALREADY_USED_MESSAGE, INVALID_CREDENTIALS_MESSAGE } from './validation';

export interface AuthUser {
  id: string;
  email: string;
}

/** Session stockée dans le trousseau sécurisé (jamais le mot de passe). */
export interface Session {
  /** Jeton d'accès JWT (courte durée). */
  token: string;
  /** Jeton de rafraîchissement (30 jours glissants, rotation à chaque usage). */
  refreshToken: string;
  user: AuthUser;
}

export class AuthError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'AuthError';
  }
}

const UNREACHABLE = 'Serveur injoignable. Vérifiez votre connexion.';

async function post(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  try {
    return await fetch(`${API_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...headers },
      body: JSON.stringify(body),
    });
  } catch {
    throw new AuthError(UNREACHABLE, 0);
  }
}

function toSession(body: { token: string; refreshToken: string; user: AuthUser }): Session {
  return { token: body.token, refreshToken: body.refreshToken, user: body.user };
}

export async function register(email: string, password: string): Promise<Session> {
  const res = await post('/api/auth/register', { email, password });
  const body = await res.json().catch(() => null);
  if (res.ok) {
    return toSession(body);
  }
  if (res.status === 409) {
    throw new AuthError(EMAIL_ALREADY_USED_MESSAGE, 409);
  }
  if (res.status === 400 && Array.isArray(body?.errors) && body.errors.length > 0) {
    throw new AuthError(body.errors.map((e: { message: string }) => e.message).join('\n'), 400);
  }
  throw new AuthError("L'inscription a échoué. Réessayez dans un instant.", res.status);
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await post('/api/auth/login', { email, password });
  const body = await res.json().catch(() => null);
  if (res.ok) {
    return toSession(body);
  }
  if (res.status === 401 || res.status === 400) {
    // Message générique : ne précise pas si c'est l'e-mail ou le mot de passe.
    throw new AuthError(INVALID_CREDENTIALS_MESSAGE, res.status);
  }
  throw new AuthError('La connexion a échoué. Réessayez dans un instant.', res.status);
}

/** Échange le jeton de rafraîchissement contre une nouvelle paire. 401 : session expirée ou révoquée. */
export async function refresh(refreshToken: string): Promise<Session> {
  const res = await post('/api/auth/refresh', { refreshToken });
  const body = await res.json().catch(() => null);
  if (res.ok) {
    return toSession(body);
  }
  throw new AuthError(body?.message ?? 'Session expirée', res.status);
}

/** Révoque la session de cet appareil côté serveur. Les erreurs réseau sont ignorées. */
export async function logout(session: Session): Promise<void> {
  await post('/api/auth/logout', { refreshToken: session.refreshToken }, { Authorization: `Bearer ${session.token}` })
    .catch(() => undefined);
}

export const INCORRECT_PASSWORD_MESSAGE = 'Mot de passe incorrect';

/** Supprime le compte connecté et toutes ses données (`DELETE /api/account`, 204). */
export async function deleteAccount(
  authFetch: (path: string, init?: RequestInit) => Promise<Response>,
  password: string,
): Promise<void> {
  let res: Response;
  try {
    res = await authFetch('/api/account', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password }),
    });
  } catch {
    throw new AuthError(UNREACHABLE, 0);
  }
  if (res.ok) return;
  if (res.status === 403 || res.status === 400) {
    throw new AuthError(INCORRECT_PASSWORD_MESSAGE, res.status);
  }
  throw new AuthError('La suppression a échoué. Réessayez dans un instant.', res.status);
}
