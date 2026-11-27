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

/** Photo prête à l'envoi (fichier local compressé). `name` : nom d'origine (import depuis la galerie). */
export interface PreparedPhoto {
  uri: string;
  width: number;
  height: number;
  name?: string;
}

/** Photo choisie dans la galerie, avant contrôle et compression. */
export interface PickedPhoto extends PreparedPhoto {
  mimeType?: string | null;
  fileSize?: number | null;
}

/** Résultat de l'envoi multiple (`POST /api/events/{id}/photos/batch`). */
export interface BatchUploadResult {
  photos: EventPhoto[];
  rejected: { index: number; filename: string | null; status: number; message: string }[];
}

/** Sélection multiple dans la galerie : au plus 10 photos en une fois (critère d'acceptation, limite de l'API). */
export const MAX_GALLERY_SELECTION = 10;
/** Formats importables : convertis en JPEG par la compression avant l'envoi. */
const ACCEPTED_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/heic', 'image/heif'];
const ACCEPTED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'heic', 'heif'];

export const SELECTION_TRUNCATED = `Vous pouvez importer ${MAX_GALLERY_SELECTION} photos au maximum à la fois : seules les ${MAX_GALLERY_SELECTION} premières ont été retenues.`;

/** Nom affiché d'une photo de la galerie. */
export function photoName(photo: PreparedPhoto, index: number): string {
  return photo.name || `photo ${index + 1}`;
}

/** Motif de refus d'une photo de la galerie (format autre que JPEG, PNG ou HEIC) ; `null` si acceptable. */
export function rejectReason(photo: PickedPhoto, index: number): string | null {
  const name = photoName(photo, index);
  const type = photo.mimeType?.toLowerCase();
  const ext = photo.name?.includes('.') ? photo.name.toLowerCase().split('.').pop() : undefined;
  // Type MIME fourni par le système ; à défaut l'extension ; sans l'un ni l'autre, la compression tranchera.
  const accepted = type ? ACCEPTED_TYPES.includes(type) : !ext || ACCEPTED_EXTENSIONS.includes(ext);
  if (!accepted) return `« ${name} » : format non pris en charge (JPEG, PNG ou HEIC uniquement).`;
  return null;
}

/** Nom du fichier JPEG envoyé (extension remplacée : la compression produit du JPEG). */
export function jpegName(photo: PreparedPhoto, index: number): string {
  const base = (photo.name ?? '').replace(/\.[^.]*$/, '').trim();
  return `${base || `photo-${index + 1}`}.jpg`;
}

/** Largeur maximale envoyée au serveur (critère d'acceptation). */
export const MAX_WIDTH = 2048;
/** Qualité JPEG après redimensionnement : ~0,5 à 1,5 Mo pour une photo 2048 px. */
export const JPEG_QUALITY = 0.8;

/** Largeur cible : inchangée si déjà ≤ MAX_WIDTH (jamais d'agrandissement), sinon MAX_WIDTH (proportions conservées). */
export function targetWidth(width: number): number | null {
  return width > MAX_WIDTH ? MAX_WIDTH : null;
}
