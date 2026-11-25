import { StatusBar } from 'expo-status-bar';
import { ActivityIndicator, View } from 'react-native';
import { AuthProvider, useAuth } from './src/auth/AuthContext';
import { EventsScreen } from './src/screens/EventsScreen';
import { RegisterScreen } from './src/screens/RegisterScreen';

function Root() {
  const { session, ready } = useAuth();
  if (!ready) {
    return (
      <View style={{ flex: 1, justifyContent: 'center' }}>
        <ActivityIndicator />
      </View>
    );
  }
  return session ? <EventsScreen /> : <RegisterScreen />;
}

export default function App() {
  return (
    <AuthProvider>
      <Root />
      <StatusBar style="auto" />
    </AuthProvider>
  );
}
