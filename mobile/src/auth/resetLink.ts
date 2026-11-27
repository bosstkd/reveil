import { useEffect, useState } from 'react';
import { Linking } from 'react-native';

const TOKEN_IN_URL = /[?&]token=([A-Za-z0-9_-]+)/;
const RAW_TOKEN = /^[A-Za-z0-9_-]{20,200}$/;

/**
 * Jeton de réinitialisation extrait d'un lien reçu par e-mail (web `https://…/reinitialiser-mot-de-passe?token=…`
 * ou mobile `reveil://reinitialiser-mot-de-passe?token=…`) ou collé seul. `null` si rien d'exploitable.
 */
export function extractResetToken(input: string | null | undefined): string | null {
  const value = (input ?? '').trim();
  if (!value) return null;
  const inUrl = TOKEN_IN_URL.exec(value);
  if (inUrl) return inUrl[1];
  return RAW_TOKEN.test(value) ? value : null;
}

function isResetLink(url: string | null | undefined): url is string {
  return !!url && url.includes('reinitialiser-mot-de-passe');
}

/**
 * Lien profond `reveil://reinitialiser-mot-de-passe?token=…` : à l'ouverture de l'application (lien touché
 * dans l'e-mail) ou pendant qu'elle tourne. Renvoie le jeton reçu et une fonction pour l'oublier.
 */
export function useResetLink(): { token: string | null; clear: () => void } {
  const [token, setToken] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    const handle = (url: string | null | undefined) => {
      const found = isResetLink(url) ? extractResetToken(url) : null;
      if (active && found) setToken(found);
    };
    Promise.resolve(Linking.getInitialURL?.())
      .then(handle)
      .catch(() => undefined);
    const sub = Linking.addEventListener?.('url', ({ url }) => handle(url));
    return () => {
      active = false;
      sub?.remove?.();
    };
  }, []);
  return { token, clear: () => setToken(null) };
}
