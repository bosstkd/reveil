import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { AuthProvider, useAuth } from './src/auth/AuthContext';
import { useDeviceRegistration, useNotificationRouting } from './src/notifications/useNotifications';
import { EventDetailScreen } from './src/screens/EventDetailScreen';
import { EventsScreen } from './src/screens/EventsScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { RegisterScreen } from './src/screens/RegisterScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';

function Root() {
  const { session, ready, authFetch } = useAuth();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  // Événement choisi dans la liste (détail, galerie et prise de photo).
  const [selected, setSelected] = useState<string | null>(null);
  // Écran des paramètres (suppression du compte).
  const [settings, setSettings] = useState(false);
  // Événement ouvert depuis une notification : affiché dès que l'utilisateur est connecté.
  const opened = useNotificationRouting();
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
  if (session && opened.eventId) return <EventDetailScreen eventId={opened.eventId} onBack={opened.close} />;
  if (session && selected) return <EventDetailScreen eventId={selected} onBack={() => setSelected(null)} />;
  if (session && settings) return <SettingsScreen onBack={() => setSettings(false)} />;
  if (session) return <EventsScreen onOpen={setSelected} onSettings={() => setSettings(true)} />;
  return mode === 'login' ? (
    <LoginScreen onRegister={() => setMode('register')} />
  ) : (
    <RegisterScreen onLogin={() => setMode('login')} />
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
