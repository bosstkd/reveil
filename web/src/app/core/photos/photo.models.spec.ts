import { BATCH_MAX_BYTES, PHOTO_MAX_BYTES, batches, formatSize, photoKind, rejectReason } from './photo.models';

const file = (name: string, type: string, size = 1) => new File([new Uint8Array(size)], name, { type });

describe('photoKind', () => {
  it('reconnaît JPEG, PNG et HEIC par le type MIME', () => {
    expect(photoKind(file('a', 'image/jpeg'))).toBe('jpeg');
    expect(photoKind(file('a', 'image/png'))).toBe('png');
    expect(photoKind(file('a', 'image/heic'))).toBe('heic');
    expect(photoKind(file('a', 'image/heif'))).toBe('heic');
  });

  it('sans type MIME : d’après l’extension', () => {
    expect(photoKind(file('IMG_1.HEIC', ''))).toBe('heic');
    expect(photoKind(file('a.JPEG', ''))).toBe('jpeg');
    expect(photoKind(file('a.png', 'application/octet-stream'))).toBe('png');
    expect(photoKind(file('a.gif', ''))).toBeNull();
    expect(photoKind(file('sans-extension', ''))).toBeNull();
  });

  it('refuse les autres formats, même avec une extension trompeuse', () => {
    expect(photoKind(file('a.gif', 'image/gif'))).toBeNull();
    expect(photoKind(file('a.webp', 'image/webp'))).toBeNull();
    expect(photoKind(file('photo.jpg', 'application/pdf'))).toBeNull();
  });
});

describe('rejectReason', () => {
  it('accepte une photo valide jusqu’à 15 Mo inclus', () => {
    expect(rejectReason(file('a.jpg', 'image/jpeg', PHOTO_MAX_BYTES))).toBeNull();
  });

  it('message explicite : format, taille, fichier vide', () => {
    expect(rejectReason(file('a.gif', 'image/gif'))).toBe('« a.gif » : format non pris en charge (JPEG, PNG ou HEIC uniquement).');
    expect(rejectReason(file('a.jpg', 'image/jpeg', 20 * 1024 * 1024))).toBe(
      '« a.jpg » : fichier trop volumineux (20 Mo ; maximum 15 Mo).',
    );
    expect(rejectReason(file('a.jpg', 'image/jpeg', 0))).toBe('« a.jpg » : fichier vide.');
  });

  it('formate la taille en Mo', () => {
    expect(formatSize(12.34 * 1024 * 1024)).toBe('12,3 Mo');
  });
});

describe('batches', () => {
  it('regroupe par 10 fichiers au plus', () => {
    const files = Array.from({ length: 23 }, (_, i) => file(`${i}.jpg`, 'image/jpeg'));
    expect(batches(files).map((b) => b.length)).toEqual([10, 10, 3]);
  });

  it('ne dépasse pas la taille maximale d’un envoi', () => {
    const big = (n: string) => file(n, 'image/jpeg', 15 * 1024 * 1024);
    const result = batches([big('a'), big('b'), big('c'), big('d')]);
    expect(result.map((b) => b.map((f) => f.name))).toEqual([['a', 'b', 'c'], ['d']]);
    expect(result.every((b) => b.reduce((s, f) => s + f.size, 0) <= BATCH_MAX_BYTES)).toBe(true);
  });

  it('aucun fichier : aucun lot', () => {
    expect(batches([])).toEqual([]);
  });
});
