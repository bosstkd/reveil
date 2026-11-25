import type { CreateEventPayload, ReveilEvent } from './events';

type AuthFetch = (path: string, init?: RequestInit) => Promise<Response>;

export class EventError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'EventError';
  }
}

const UNREACHABLE = 'Serveur injoignable. Vérifiez votre connexion.';

export async function listEvents(authFetch: AuthFetch): Promise<ReveilEvent[]> {
  let res: Response;
  try {
    res = await authFetch('/api/events');
  } catch {
    throw new EventError(UNREACHABLE, 0);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && Array.isArray(body)) return body as ReveilEvent[];
  throw new EventError('Impossible de charger vos événements.', res.status);
}

export async function createEvent(authFetch: AuthFetch, payload: CreateEventPayload): Promise<ReveilEvent> {
  let res: Response;
  try {
    res = await authFetch('/api/events', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch {
    throw new EventError(UNREACHABLE, 0);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && body?.id) return body as ReveilEvent;
  if (res.status === 400 && Array.isArray(body?.errors) && body.errors.length > 0) {
    throw new EventError(body.errors.map((e: { message: string }) => e.message).join('\n'), 400);
  }
  throw new EventError("La création de l'événement a échoué. Réessayez dans un instant.", res.status);
}
