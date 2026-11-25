import { API_URL } from '../config';
import { EMAIL_ALREADY_USED_MESSAGE } from './validation';

export interface AuthUser {
  id: string;
  email: string;
}

export interface Session {
  token: string;
  user: AuthUser;
}

export class RegisterError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'RegisterError';
  }
}

export async function register(email: string, password: string): Promise<Session> {
  let res: Response;
  try {
    res = await fetch(`${API_URL}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new RegisterError('Serveur injoignable. Vérifiez votre connexion.', 0);
  }

  const body = await res.json().catch(() => null);
  if (res.ok) {
    return body as Session;
  }
  if (res.status === 409) {
    throw new RegisterError(EMAIL_ALREADY_USED_MESSAGE, 409);
  }
  if (res.status === 400 && Array.isArray(body?.errors) && body.errors.length > 0) {
    throw new RegisterError(body.errors.map((e: { message: string }) => e.message).join('\n'), 400);
  }
  throw new RegisterError("L'inscription a échoué. Réessayez dans un instant.", res.status);
}
