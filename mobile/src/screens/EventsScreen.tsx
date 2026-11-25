import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';

/** Liste des événements de l'utilisateur. Contenu réel : stories suivantes. */
export function EventsScreen() {
  const { session, logout } = useAuth();
  const [leaving, setLeaving] = useState(false);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header">
          Mes événements
        </Text>
        <Pressable
          testID="logout"
          accessibilityRole="button"
          disabled={leaving}
          onPress={() => {
            setLeaving(true);
            void logout();
          }}
          style={styles.logout}
        >
          <Text style={styles.logoutText}>Se déconnecter</Text>
        </Pressable>
      </View>
      <Text style={styles.who}>Connecté en tant que {session?.user.email}</Text>
      <Text testID="events-empty">Aucun événement pour le moment.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24, paddingTop: 64, backgroundColor: '#fff' },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: 26, fontWeight: '700' },
  logout: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10 },
  logoutText: { color: '#3b5bdb' },
  who: { color: '#666', marginVertical: 8 },
});
