import { Injectable } from '@angular/core';

/** Qualité JPEG de la conversion : proche de l'original, taille raisonnable. */
export const HEIC_JPEG_QUALITY = 0.9;

/**
 * Conversion HEIC → JPEG dans le navigateur (l'API n'accepte que JPEG et PNG). La bibliothèque `heic-to`
 * (libheif compilé en WebAssembly, ~2,5 Mo) est chargée à la demande, au premier fichier HEIC.
 */
@Injectable({ providedIn: 'root' })
export class HeicConverter {
  async toJpeg(file: File): Promise<File> {
    const { heicTo } = await import('heic-to');
    const blob = await heicTo({ blob: file, type: 'image/jpeg', quality: HEIC_JPEG_QUALITY });
    const name = file.name.replace(/\.(heic|heif)$/i, '') + '.jpg';
    return new File([blob], name, { type: 'image/jpeg', lastModified: file.lastModified });
  }
}
