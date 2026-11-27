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

/** Confirmation de demande : identique que le compte existe ou non (même texte que l'API). */
export const RESET_REQUESTED_MESSAGE =
  'Si un compte est associé à cet e-mail, un lien de réinitialisation vient de lui être envoyé. Il est valable 1 heure.';
/** Lien inconnu, expiré (1 h) ou déjà utilisé (même texte que l'API). */
export const INVALID_RESET_LINK_MESSAGE = 'Ce lien de réinitialisation est invalide ou a expiré';

/** Demande un lien de réinitialisation par e-mail (`202`, même réponse que le compte existe ou non). */
export async function requestPasswordReset(email: string): Promise<void> {
  const res = await post('/api/auth/password-reset/request', { email });
  if (res.ok) return;
  const body = await res.json().catch(() => null);
  if (res.status === 400 && Array.isArray(body?.errors) && body.errors.length > 0) {
    throw new AuthError(body.errors.map((e: { message: string }) => e.message).join('\n'), 400);
  }
  throw new AuthError("La demande n'a pas pu être envoyée. Réessayez dans un instant.", res.status);
}

/**
 * Enregistre le nouveau mot de passe (`204`). Le serveur ferme alors toutes les sessions du compte.
 * Lien invalide/expiré/déjà utilisé : {@link AuthError} 400 avec {@link INVALID_RESET_LINK_MESSAGE}.
 */
export async function confirmPasswordReset(token: string, password: string): Promise<void> {
  const res = await post('/api/auth/password-reset/confirm', { token, password });
  if (res.ok) return;
  const body = await res.json().catch(() => null);
  const errors: { field: string; message: string }[] = Array.isArray(body?.errors) ? body.errors : [];
  if (res.status === 400 && errors.some((e) => e.field === 'token')) {
    throw new AuthError(INVALID_RESET_LINK_MESSAGE, 400);
  }
  if (res.status === 400 && errors.length > 0) {
    throw new AuthError(errors.map((e) => e.message).join('\n'), 400);
  }
  throw new AuthError('La réinitialisation a échoué. Réessayez dans un instant.', res.status);
}
