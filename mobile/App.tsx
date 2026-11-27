import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { AuthProvider, useAuth } from './src/auth/AuthContext';
import { useResetLink } from './src/auth/resetLink';
import { useDeviceRegistration, useNotificationRouting } from './src/notifications/useNotifications';
import { EventDetailScreen } from './src/screens/EventDetailScreen';
import { EventsScreen } from './src/screens/EventsScreen';
import { ForgotPasswordScreen } from './src/screens/ForgotPasswordScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { RegisterScreen } from './src/screens/RegisterScreen';
import { ResetPasswordScreen } from './src/screens/ResetPasswordScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';

export const PASSWORD_CHANGED_NOTICE = 'Votre mot de passe a été modifié. Connectez-vous avec le nouveau mot de passe.';

type AuthScreen = 'login' | 'register' | 'forgot' | 'reset';

function Root() {
  const { session, ready, authFetch, logout } = useAuth();
  const [mode, setModeState] = useState<AuthScreen>('login');
  const [notice, setNotice] = useState<string | null>(null);
  const setMode = (next: AuthScreen) => {
    setNotice(null);
    setModeState(next);
  };
  // Événement choisi dans la liste (détail, galerie et prise de photo).
  const [selected, setSelected] = useState<string | null>(null);
  // Écran des paramètres (suppression du compte).
  const [settings, setSettings] = useState(false);
  // Événement ouvert depuis une notification : affiché dès que l'utilisateur est connecté.
  const opened = useNotificationRouting();
  // Lien profond reveil://reinitialiser-mot-de-passe?token=… (e-mail de réinitialisation).
  const resetLink = useResetLink();
  useDeviceRegistration(session?.user.id, authFetch);
  useEffect(() => {
    if (!session) {
      setSelected(null);
      setSettings(false);
    }
  }, [session]);
  if (!ready) {
    return (
      <View style={{ flex: 1, justifyContent: 'center' }}>
        <ActivityIndicator />
      </View>
    );
  }
  if (resetLink.token || (!session && mode === 'reset')) {
    const leave = (next: AuthScreen) => {
      resetLink.clear();
      setMode(next);
    };
    return (
      <ResetPasswordScreen
        key={resetLink.token ?? 'saisie'}
        initialToken={resetLink.token}
        onBack={() => leave('login')}
        onRequestNew={() => leave('forgot')}
        onDone={() => {
          leave('login');
          setNotice(PASSWORD_CHANGED_NOTICE);
          // Le serveur a fermé toutes les sessions du compte : la session locale éventuelle est effacée.
          if (session) void logout();
        }}
      />
    );
  }
  if (session && opened.eventId) return <EventDetailScreen eventId={opened.eventId} onBack={opened.close} />;
  if (session && selected) return <EventDetailScreen eventId={selected} onBack={() => setSelected(null)} />;
  if (session && settings) return <SettingsScreen onBack={() => setSettings(false)} />;
  if (session) return <EventsScreen onOpen={setSelected} onSettings={() => setSettings(true)} />;
  if (mode === 'register') return <RegisterScreen onLogin={() => setMode('login')} />;
  if (mode === 'forgot') {
    return <ForgotPasswordScreen onBack={() => setMode('login')} onHaveLink={() => setMode('reset')} />;
  }
  return (
    <LoginScreen onRegister={() => setMode('register')} onForgotPassword={() => setMode('forgot')} notice={notice} />
  );
}

export default function App() {
  return (
    <AuthProvider>
      <Root />
      <StatusBar style="auto" />
    </AuthProvider>
  );
}
