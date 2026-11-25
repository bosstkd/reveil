import * as SecureStore from 'expo-secure-store';
import { ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { API_URL } from '../config';
import * as api from './authApi';
import type { Session } from './authApi';

const STORAGE_KEY = 'reveil.session';

interface AuthState {
  session: Session | null;
  ready: boolean;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** fetch vers l'API avec le jeton Bearer ; sur 401, renouvelle le jeton une fois et rejoue. */
  authFetch: (path: string, init?: RequestInit) => Promise<Response>;
}

const AuthContext = createContext<AuthState | null>(null);

function parseStored(raw: string | null): Session | null {
  if (!raw) return null;
  try {
    const s = JSON.parse(raw) as Partial<Session>;
    return s.token && s.refreshToken && s.user ? (s as Session) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);
  const sessionRef = useRef<Session | null>(null);
  const refreshing = useRef<Promise<Session | null> | null>(null);

  const save = useCallback(async (s: Session | null) => {
    sessionRef.current = s;
    setSession(s);
    // Jetons dans le trousseau sécurisé (Keychain / Keystore) ; le mot de passe n'est jamais conservé.
    if (s) {
      await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(s)).catch(() => undefined);
    } else {
      await SecureStore.deleteItemAsync(STORAGE_KEY).catch(() => undefined);
    }
  }, []);

  /**
   * Renouvelle la session (une seule requête à la fois : le jeton change à chaque usage).
   * 401 → session expirée/révoquée : déconnexion locale. Réseau indisponible → session conservée.
   */
  const refreshSession = useCallback((): Promise<Session | null> => {
    const current = sessionRef.current;
    if (!current) return Promise.resolve(null);
    if (!refreshing.current) {
      refreshing.current = api
        .refresh(current.refreshToken)
        .then(async (s) => {
          await save(s);
          return s;
        })
        .catch(async (e) => {
          if (e instanceof api.AuthError && (e.status === 401 || e.status === 400)) {
            await save(null);
            return null;
          }
          return sessionRef.current;
        })
        .finally(() => {
          refreshing.current = null;
        });
    }
    return refreshing.current;
  }, [save]);

  useEffect(() => {
    SecureStore.getItemAsync(STORAGE_KEY)
      .catch(() => null)
      .then((raw) => {
        const stored = parseStored(raw);
        sessionRef.current = stored;
        setSession(stored);
        setReady(true);
        // Session restaurée sans nouvelle saisie ; le rafraîchissement prolonge ses 30 jours.
        if (stored) void refreshSession();
      });
  }, [refreshSession]);

  const register = useCallback(async (email: string, password: string) => {
    await save(await api.register(email, password));
  }, [save]);

  const login = useCallback(async (email: string, password: string) => {
    await save(await api.login(email, password));
  }, [save]);

  const logout = useCallback(async () => {
    const current = sessionRef.current;
    await save(null);
    if (current) await api.logout(current);
  }, [save]);

  const authFetch = useCallback(async (path: string, init: RequestInit = {}) => {
    const call = (token: string | undefined) =>
      fetch(`${API_URL}${path}`, {
        ...init,
        headers: { Accept: 'application/json', ...(init.headers ?? {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      });
    const sent = sessionRef.current?.token;
    const res = await call(sent);
    if (res.status !== 401 || !sessionRef.current) return res;
    // Jeton déjà renouvelé entre-temps (autre requête) : simple rejeu, sinon rafraîchissement.
    const renewed = sessionRef.current.token !== sent ? sessionRef.current : await refreshSession();
    return renewed ? call(renewed.token) : res;
  }, [refreshSession]);

  const value = useMemo(
    () => ({ session, ready, register, login, logout, authFetch }),
    [session, ready, register, login, logout, authFetch],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth doit être utilisé dans <AuthProvider>');
  return ctx;
}
