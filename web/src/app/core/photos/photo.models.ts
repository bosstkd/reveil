/** Photo d'un événement, telle que renvoyée par l'API. */
export interface EventPhoto {
  id: string;
  eventId: string;
  contentType: string;
  size: number;
  width: number;
  height: number;
  createdAt: string;
  /** Chemin d'API protégé de l'image d'origine (ex. « /api/events/e1/photos/p1/content »). */
  url: string;
  /** Chemin d'API protégé de la miniature. */
  thumbnailUrl: string;
}

/** Types acceptés par l'API (contrôlés côté serveur sur le contenu). */
export const PHOTO_ACCEPT = 'image/jpeg,image/png';
/** Taille maximale acceptée par l'API. */
export const PHOTO_MAX_BYTES = 10 * 1024 * 1024;
export const PHOTO_TOO_LARGE = 'Photo trop volumineuse (maximum 10 Mo).';
export const PHOTO_UNSUPPORTED = 'Format non pris en charge : choisissez une photo JPEG ou PNG.';
export const PHOTO_UPLOAD_FAILED = "L'envoi de la photo a échoué. Réessayez dans un instant.";

/**
 * Les URL renvoyées par l'API sont absolues (« /api/... ») : elles sont rendues relatives pour fonctionner
 * sous n'importe quel base href (ex. /test/reveil/).
 */
export function relativeApiUrl(url: string): string {
  return url.replace(/^\/+/, '');
}
