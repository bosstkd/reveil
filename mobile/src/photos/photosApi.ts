import type { EventPhoto, PreparedPhoto } from './photos';

type AuthFetch = (path: string, init?: RequestInit) => Promise<Response>;

export class PhotoError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'PhotoError';
  }
}

/** Au-delà, l'envoi est abandonné et l'utilisateur peut réessayer (objectif : < 10 s en 4G). */
export const UPLOAD_TIMEOUT_MS = 30_000;

export const UPLOAD_FAILED = "L'envoi de la photo a échoué. Vérifiez votre connexion et réessayez.";
const UNREACHABLE = 'Serveur injoignable. Vérifiez votre connexion.';

/** GET /api/events/{id}/photos : galerie de l'événement, la plus récente d'abord. */
export async function listPhotos(authFetch: AuthFetch, eventId: string): Promise<EventPhoto[]> {
  let res: Response;
  try {
    res = await authFetch(`/api/events/${encodeURIComponent(eventId)}/photos`);
  } catch {
    throw new PhotoError(UNREACHABLE, 0);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && Array.isArray(body)) return body as EventPhoto[];
  throw new PhotoError('Impossible de charger les photos.', res.status);
}

/** POST multipart /api/events/{id}/photos (partie « file », JPEG compressé). */
export async function uploadPhoto(authFetch: AuthFetch, eventId: string, photo: PreparedPhoto): Promise<EventPhoto> {
  const form = new FormData();
  // Forme React Native d'un fichier local dans FormData.
  form.append('file', { uri: photo.uri, name: 'photo.jpg', type: 'image/jpeg' } as unknown as Blob);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPLOAD_TIMEOUT_MS);
  let res: Response;
  try {
    res = await authFetch(`/api/events/${encodeURIComponent(eventId)}/photos`, {
      method: 'POST',
      body: form,
      signal: controller.signal,
    });
  } catch {
    throw new PhotoError(UPLOAD_FAILED, 0);
  } finally {
    clearTimeout(timer);
  }
  const body = await res.json().catch(() => null);
  if (res.ok && body?.id) return body as EventPhoto;
  // Refus explicite du serveur (type, taille, événement supprimé) : son message est plus utile.
  if ([400, 404, 413, 415].includes(res.status) && typeof body?.message === 'string') {
    throw new PhotoError(body.message, res.status);
  }
  throw new PhotoError(UPLOAD_FAILED, res.status);
}
