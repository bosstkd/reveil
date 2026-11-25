/**
 * URL de base de l'API. Sur mobile une URL absolue est nécessaire :
 * définir EXPO_PUBLIC_API_URL (ex. http://192.168.1.10:8081 ou https://hote/test/reveil).
 */
export const API_URL = (process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
