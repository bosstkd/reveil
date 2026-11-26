/** Photo d'un événement telle que renvoyée par l'API. `url` / `thumbnailUrl` : chemins d'API protégés. */
export interface EventPhoto {
  id: string;
  eventId: string;
  contentType: string;
  size: number;
  width: number;
  height: number;
  createdAt: string;
  url: string;
  thumbnailUrl: string;
}

/** Photo prête à l'envoi (fichier local compressé). */
export interface PreparedPhoto {
  uri: string;
  width: number;
  height: number;
}

/** Largeur maximale envoyée au serveur (critère d'acceptation). */
export const MAX_WIDTH = 2048;
/** Qualité JPEG après redimensionnement : ~0,5 à 1,5 Mo pour une photo 2048 px. */
export const JPEG_QUALITY = 0.8;

/** Largeur cible : inchangée si déjà ≤ MAX_WIDTH (jamais d'agrandissement), sinon MAX_WIDTH (proportions conservées). */
export function targetWidth(width: number): number | null {
  return width > MAX_WIDTH ? MAX_WIDTH : null;
}
