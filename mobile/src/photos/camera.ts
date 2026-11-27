import { SaveFormat, ImageManipulator } from 'expo-image-manipulator';
import {
  getCameraPermissionsAsync,
  launchCameraAsync,
  launchImageLibraryAsync,
  requestCameraPermissionsAsync,
} from 'expo-image-picker';
import { JPEG_QUALITY, MAX_GALLERY_SELECTION, PickedPhoto, PreparedPhoto, targetWidth } from './photos';

/** granted : autorisé ; denied : refusé (redemandable) ; blocked : refusé définitivement (réglages du système). */
export type CameraAccess = 'granted' | 'denied' | 'blocked';

/**
 * Autorisation d'accès à l'appareil photo, demandée au premier usage (pas au lancement de l'application).
 * Déjà accordée : aucune boîte de dialogue. Refusée sans possibilité de redemander : « blocked ».
 */
export async function ensureCameraPermission(): Promise<CameraAccess> {
  const current = await getCameraPermissionsAsync();
  if (current.granted) return 'granted';
  if (!current.canAskAgain) return 'blocked';
  const asked = await requestCameraPermissionsAsync();
  if (asked.granted) return 'granted';
  return asked.canAskAgain ? 'denied' : 'blocked';
}

/** Ouvre l'appareil photo ; `null` si l'utilisateur annule. Qualité maximale : la compression se fait ensuite, une seule fois. */
export async function takePhoto(): Promise<PreparedPhoto | null> {
  const result = await launchCameraAsync({ mediaTypes: ['images'], quality: 1, exif: false });
  if (result.canceled || !result.assets?.length) return null;
  const { uri, width, height } = result.assets[0];
  return { uri, width, height };
}

/**
 * Sélecteur de la galerie du système, en sélection multiple (10 photos au plus). Aucune autorisation n'est
 * demandée : iOS 14+ (PHPicker) et Android (Photo Picker) ne donnent accès qu'aux photos choisies.
 * `null` si l'utilisateur annule. `truncated` : le système a rendu plus de 10 photos (anciens Android qui
 * ignorent la limite) ; seules les 10 premières sont gardées.
 */
export async function pickFromGallery(): Promise<{ photos: PickedPhoto[]; truncated: boolean } | null> {
  const result = await launchImageLibraryAsync({
    mediaTypes: ['images'],
    allowsMultipleSelection: true,
    selectionLimit: MAX_GALLERY_SELECTION,
    orderedSelection: true,
    quality: 1,
    exif: false,
  });
  if (result.canceled || !result.assets?.length) return null;
  const photos = result.assets.map(({ uri, width, height, fileName, mimeType, fileSize }) => ({
    uri,
    width,
    height,
    name: fileName ?? undefined,
    mimeType,
    fileSize,
  }));
  return { photos: photos.slice(0, MAX_GALLERY_SELECTION), truncated: photos.length > MAX_GALLERY_SELECTION };
}

/** Redimensionne (largeur ≤ 2048 px, proportions conservées) et réencode en JPEG avant envoi. */
export async function compressPhoto(photo: PreparedPhoto): Promise<PreparedPhoto> {
  const context = ImageManipulator.manipulate(photo.uri);
  const width = targetWidth(photo.width);
  if (width) context.resize({ width });
  const image = await context.renderAsync();
  const saved = await image.saveAsync({ compress: JPEG_QUALITY, format: SaveFormat.JPEG });
  return { uri: saved.uri, width: saved.width, height: saved.height, name: photo.name };
}
