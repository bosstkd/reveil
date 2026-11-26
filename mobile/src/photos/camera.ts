import { SaveFormat, ImageManipulator } from 'expo-image-manipulator';
import { getCameraPermissionsAsync, launchCameraAsync, requestCameraPermissionsAsync } from 'expo-image-picker';
import { JPEG_QUALITY, PreparedPhoto, targetWidth } from './photos';

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

/** Redimensionne (largeur ≤ 2048 px, proportions conservées) et réencode en JPEG avant envoi. */
export async function compressPhoto(photo: PreparedPhoto): Promise<PreparedPhoto> {
  const context = ImageManipulator.manipulate(photo.uri);
  const width = targetWidth(photo.width);
  if (width) context.resize({ width });
  const image = await context.renderAsync();
  const saved = await image.saveAsync({ compress: JPEG_QUALITY, format: SaveFormat.JPEG });
  return { uri: saved.uri, width: saved.width, height: saved.height };
}
