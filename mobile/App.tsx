import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { AuthProvider, useAuth } from './src/auth/AuthContext';
import { EventsScreen } from './src/screens/EventsScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { RegisterScreen } from './src/screens/RegisterScreen';

function Root() {
  const { session, ready } = useAuth();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  if (!ready) {
    return (
      <View style={{ flex: 1, justifyContent: 'center' }}>
        <ActivityIndicator />
      </View>
    );
  }
  if (session) return <EventsScreen />;
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
