import { PAGE_SIZE } from './events';
import type { CreateEventPayload, EventPage, EventPeriod, ReveilEvent } from './events';

type AuthFetch = (path: string, init?: RequestInit) => Promise<Response>;

export class EventError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'EventError';
  }
}

const UNREACHABLE = 'Serveur injoignable. Vérifiez votre connexion.';

/** GET /api/events?when=…&page=…&size=… : une page des événements de l'utilisateur connecté. */
export async function listEvents(authFetch: AuthFetch, when: EventPeriod, page = 0): Promise<EventPage> {
  let res: Response;
  try {
    res = await authFetch(`/api/events?when=${when}&page=${page}&size=${PAGE_SIZE}`);
  } catch {
    throw new EventError(UNREACHABLE, 0);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && Array.isArray(body?.items)) return body as EventPage;
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

/** Message affiché quand l'événement ouvert (notification, lien) n'existe plus. */
export const EVENT_NOT_FOUND = "Cet événement n'existe plus.";

/** GET /api/events/{id} : détail d'un événement de l'utilisateur connecté. */
export async function getEvent(authFetch: AuthFetch, id: string): Promise<ReveilEvent> {
  let res: Response;
  try {
    res = await authFetch(`/api/events/${encodeURIComponent(id)}`);
  } catch {
    throw new EventError(UNREACHABLE, 0);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && body?.id) return body as ReveilEvent;
  if (res.status === 404) throw new EventError(EVENT_NOT_FOUND, 404);
  throw new EventError("Impossible de charger l'événement.", res.status);
}
