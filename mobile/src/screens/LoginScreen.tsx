import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { AuthError } from '../auth/authApi';
import { useAuth } from '../auth/AuthContext';
import { INVALID_CREDENTIALS_MESSAGE } from '../auth/validation';

export function LoginScreen({ onRegister }: { onRegister: () => void }) {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    if (submitting) return;
    // Champs vides : même message générique que des identifiants refusés.
    if (!email.trim() || !password) {
      setError(INVALID_CREDENTIALS_MESSAGE);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await login(email.trim(), password);
      // Succès : AuthProvider ouvre la session, App bascule sur la liste des événements.
    } catch (e) {
      setError(e instanceof AuthError ? e.message : 'La connexion a échoué.');
      setPassword('');
      setSubmitting(false);
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        Se connecter
      </Text>

      <Text style={styles.label}>E-mail</Text>
      <TextInput
        testID="login-email"
        style={styles.input}
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        textContentType="username"
        placeholder="vous@exemple.fr"
      />

      <Text style={styles.label}>Mot de passe</Text>
      <TextInput
        testID="login-password"
        style={styles.input}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoComplete="current-password"
        textContentType="password"
      />

      {error ? (
        <Text testID="login-error" accessibilityRole="alert" style={styles.error}>
          {error}
        </Text>
      ) : null}

      <Pressable
        testID="login-submit"
        accessibilityRole="button"
        style={[styles.button, submitting && styles.buttonDisabled]}
        onPress={submit}
        disabled={submitting}
      >
        {submitting ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Se connecter</Text>}
      </Pressable>

      <Pressable testID="to-register" accessibilityRole="link" onPress={onRegister} style={styles.switch}>
        <Text style={styles.link}>Pas encore de compte ? Créer un compte</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#fff' },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 24 },
  label: { marginTop: 12, marginBottom: 4, fontWeight: '600' },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 8, padding: 12, fontSize: 16 },
  error: { color: '#c92a2a', marginTop: 8 },
  button: { marginTop: 24, backgroundColor: '#3b5bdb', borderRadius: 8, padding: 14, alignItems: 'center' },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  switch: { marginTop: 20, alignItems: 'center' },
  link: { color: '#3b5bdb' },
});
