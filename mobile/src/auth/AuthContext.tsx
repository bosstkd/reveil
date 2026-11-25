import * as SecureStore from 'expo-secure-store';
import { ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import * as api from './authApi';
import type { Session } from './authApi';

const STORAGE_KEY = 'reveil.session';

interface AuthState {
  session: Session | null;
  ready: boolean;
  register: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    SecureStore.getItemAsync(STORAGE_KEY)
      .then((raw) => setSession(raw ? (JSON.parse(raw) as Session) : null))
      .catch(() => setSession(null))
      .finally(() => setReady(true));
  }, []);

  const register = useCallback(async (email: string, password: string) => {
    const s = await api.register(email, password);
    // Jeton stocké dans le trousseau sécurisé ; le mot de passe n'est jamais conservé.
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(s)).catch(() => undefined);
    setSession(s);
  }, []);

  const logout = useCallback(async () => {
    await SecureStore.deleteItemAsync(STORAGE_KEY).catch(() => undefined);
    setSession(null);
  }, []);

  const value = useMemo(() => ({ session, ready, register, logout }), [session, ready, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth doit être utilisé dans <AuthProvider>');
  return ctx;
}
