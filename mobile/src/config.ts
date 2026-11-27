/**
 * URL de base de l'API. Sur mobile une URL absolue est nécessaire :
 * définir EXPO_PUBLIC_API_URL (ex. https://hote/test/reveil ; http://192.168.1.10:8081 en développement seulement).
 * Hors développement, une URL non HTTPS est refusée : toutes les communications passent par HTTPS.
 */
export function resolveApiUrl(raw: string | undefined, dev: boolean): string {
  const url = (raw ?? 'http://localhost:8081').trim().replace(/\/+$/, '');
  if (!dev && !/^https:\/\//i.test(url)) {
    throw new Error("EXPO_PUBLIC_API_URL doit être une URL HTTPS (communications chiffrées obligatoires).");
  }
  return url;
}

export const API_URL = resolveApiUrl(process.env.EXPO_PUBLIC_API_URL, typeof __DEV__ !== 'undefined' && __DEV__);
