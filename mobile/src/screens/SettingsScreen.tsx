import { useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';

/** Paramètres du compte : suppression définitive du compte et de toutes ses données (RGPD). */
export function SettingsScreen({ onBack }: { onBack: () => void }) {
  const { session, deleteAccount } = useAuth();
  const [password, setPassword] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setDeleting(true);
    setError(null);
    try {
      // Succès : la session est fermée, l'application revient à l'écran de connexion.
      await deleteAccount(password);
    } catch (e) {
      setDeleting(false);
      setPassword('');
      setError((e as Error).message);
    }
  };

  const confirm = () => {
    if (deleting) return;
    if (!password) {
      setError('Saisissez votre mot de passe pour confirmer.');
      return;
    }
    Alert.alert(
      'Supprimer mon compte ?',
      'Vos événements, rappels, photos et appareils enregistrés seront supprimés définitivement. Cette action est irréversible.',
      [
        { text: 'Annuler', style: 'cancel' },
        { text: 'Supprimer', style: 'destructive', onPress: () => void submit() },
      ],
    );
  };

  return (
    <ScrollView testID="settings-screen" style={styles.scroll} contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header">
          Paramètres
        </Text>
        <Pressable testID="settings-back" accessibilityRole="button" onPress={onBack} style={styles.outline}>
          <Text style={styles.outlineText}>Retour</Text>
        </Pressable>
      </View>
      <Text style={styles.who}>Connecté en tant que {session?.user.email}</Text>

      <View style={styles.danger}>
        <Text style={styles.dangerTitle}>Supprimer mon compte</Text>
        <Text style={styles.text}>
          Votre compte, vos événements, vos rappels, vos photos et les appareils enregistrés pour les notifications
          seront supprimés définitivement (au plus tard sous 24 heures).
        </Text>
        <Text style={styles.label}>Confirmez avec votre mot de passe</Text>
        <TextInput
          testID="delete-password"
          style={styles.input}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoCapitalize="none"
          autoComplete="current-password"
          textContentType="password"
          editable={!deleting}
        />
        {error ? (
          <Text testID="delete-error" accessibilityRole="alert" style={styles.error}>
            {error}
          </Text>
        ) : null}
        <Pressable
          testID="delete-account"
          accessibilityRole="button"
          disabled={deleting}
          onPress={confirm}
          style={[styles.dangerButton, deleting && styles.disabled]}
        >
          {deleting ? <ActivityIndicator color="#fff" /> : <Text style={styles.dangerText}>Supprimer définitivement</Text>}
        </Pressable>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: '#fff' },
  container: { padding: 24, paddingTop: 64 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: 26, fontWeight: '700' },
  outline: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10 },
  outlineText: { color: '#3b5bdb' },
  who: { color: '#666', marginVertical: 8 },
  danger: { marginTop: 24, padding: 16, borderWidth: 1, borderColor: '#ffc9c9', borderRadius: 8, backgroundColor: '#fff5f5' },
  dangerTitle: { color: '#c92a2a', fontSize: 18, fontWeight: '600', marginBottom: 8 },
  text: { color: '#333', marginBottom: 12 },
  label: { marginBottom: 4 },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 6, padding: 10, fontSize: 16, backgroundColor: '#fff' },
  error: { color: '#c92a2a', marginTop: 8 },
  dangerButton: { marginTop: 12, backgroundColor: '#c92a2a', borderRadius: 8, padding: 12, alignItems: 'center' },
  disabled: { opacity: 0.6 },
  dangerText: { color: '#fff', fontWeight: '600' },
});
