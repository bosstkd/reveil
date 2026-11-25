import { StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';

/** Liste des événements de l'utilisateur. Contenu réel : stories suivantes. */
export function EventsScreen() {
  const { session } = useAuth();
  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        Mes événements
      </Text>
      <Text style={styles.who}>Connecté en tant que {session?.user.email}</Text>
      <Text testID="events-empty">Aucun événement pour le moment.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24, paddingTop: 64, backgroundColor: '#fff' },
  title: { fontSize: 26, fontWeight: '700' },
  who: { color: '#666', marginVertical: 8 },
});
