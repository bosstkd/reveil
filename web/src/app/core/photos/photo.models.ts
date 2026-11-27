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

/** Résultat de l'envoi multiple (`POST api/events/{id}/photos/batch`) : chaque fichier est traité indépendamment. */
export interface BatchUploadResult {
  /** Photos enregistrées, dans l'ordre d'envoi. */
  photos: EventPhoto[];
  rejected: RejectedFile[];
}

export interface RejectedFile {
  /** Position dans l'envoi (à partir de 0). */
  index: number;
  filename: string | null;
  status: number;
  message: string;
}

/**
 * Fichiers proposés par le sélecteur : JPEG, PNG ou HEIC (photos d'iPhone). Les HEIC sont convertis en JPEG dans
 * le navigateur avant l'envoi (l'API n'accepte que JPEG et PNG, contrôlés sur le contenu).
 */
export const PHOTO_ACCEPT = 'image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif';
/** Taille maximale d'un fichier importé (et accepté par l'API). */
export const PHOTO_MAX_MB = 15;
export const PHOTO_MAX_BYTES = PHOTO_MAX_MB * 1024 * 1024;
/** Limites d'un envoi multiple (API : 10 fichiers, requête ≤ 60 Mo) : les fichiers sont regroupés en lots. */
export const BATCH_MAX_FILES = 10;
export const BATCH_MAX_BYTES = 45 * 1024 * 1024;

export const PHOTO_TOO_LARGE = `Photo trop volumineuse (maximum ${PHOTO_MAX_MB} Mo).`;
export const PHOTO_UNSUPPORTED = 'Format non pris en charge : choisissez une photo JPEG, PNG ou HEIC.';
export const PHOTO_UPLOAD_FAILED = "L'envoi de la photo a échoué. Réessayez dans un instant.";
export const HEIC_CONVERSION_FAILED = 'conversion de la photo HEIC impossible.';

export type PhotoKind = 'jpeg' | 'png' | 'heic';

/** Format d'après le type MIME, à défaut l'extension (HEIC souvent sans type MIME hors Safari). `null` : refusé. */
export function photoKind(file: File): PhotoKind | null {
  const type = file.type.toLowerCase();
  if (type === 'image/jpeg' || type === 'image/jpg' || type === 'image/pjpeg') return 'jpeg';
  if (type === 'image/png') return 'png';
  if (type === 'image/heic' || type === 'image/heif' || type === 'image/heic-sequence' || type === 'image/heif-sequence') {
    return 'heic';
  }
  if (type && type !== 'application/octet-stream') return null;
  const ext = file.name.toLowerCase().split('.').pop();
  if (ext === 'jpg' || ext === 'jpeg') return 'jpeg';
  if (ext === 'png') return 'png';
  if (ext === 'heic' || ext === 'heif') return 'heic';
  return null;
}

/** Taille lisible (« 12,4 Mo »). */
export function formatSize(bytes: number): string {
  const mb = bytes / (1024 * 1024);
  return `${mb.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} Mo`;
}

/** Motif de refus local d'un fichier (format, taille), préfixé de son nom ; `null` s'il est acceptable. */
export function rejectReason(file: File): string | null {
  if (!photoKind(file)) {
    return `« ${file.name} » : format non pris en charge (JPEG, PNG ou HEIC uniquement).`;
  }
  if (file.size === 0) {
    return `« ${file.name} » : fichier vide.`;
  }
  if (file.size > PHOTO_MAX_BYTES) {
    return `« ${file.name} » : fichier trop volumineux (${formatSize(file.size)} ; maximum ${PHOTO_MAX_MB} Mo).`;
  }
  return null;
}

/** Regroupe les fichiers en lots d'au plus `maxFiles` fichiers et `maxBytes` octets (ordre conservé). */
export function batches(files: File[], maxFiles = BATCH_MAX_FILES, maxBytes = BATCH_MAX_BYTES): File[][] {
  const result: File[][] = [];
  let current: File[] = [];
  let size = 0;
  for (const file of files) {
    if (current.length && (current.length >= maxFiles || size + file.size > maxBytes)) {
      result.push(current);
      current = [];
      size = 0;
    }
    current.push(file);
    size += file.size;
  }
  if (current.length) result.push(current);
  return result;
}

/**
 * Les URL renvoyées par l'API sont absolues (« /api/... ») : elles sont rendues relatives pour fonctionner
 * sous n'importe quel base href (ex. /test/reveil/).
 */
export function relativeApiUrl(url: string): string {
  return url.replace(/^\/+/, '');
}
