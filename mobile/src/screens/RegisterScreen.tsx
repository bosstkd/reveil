import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { RegisterError } from '../auth/authApi';
import { useAuth } from '../auth/AuthContext';
import { PASSWORD_MIN_LENGTH, validateEmail, validatePassword } from '../auth/validation';

export function RegisterScreen() {
  const { register } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<{ email?: string | null; password?: string | null }>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    const next = { email: validateEmail(email), password: validatePassword(password) };
    setErrors(next);
    if (next.email || next.password || submitting) return;

    setSubmitting(true);
    setServerError(null);
    try {
      await register(email.trim(), password);
      // Succès : AuthProvider ouvre la session, App bascule sur la liste des événements.
    } catch (e) {
      setServerError(e instanceof RegisterError ? e.message : "L'inscription a échoué.");
      setSubmitting(false);
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        Créer un compte
      </Text>

      <Text style={styles.label}>E-mail</Text>
      <TextInput
        testID="email"
        style={styles.input}
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        textContentType="emailAddress"
        placeholder="vous@exemple.fr"
      />
      {errors.email ? <Text testID="email-error" style={styles.error}>{errors.email}</Text> : null}

      <Text style={styles.label}>Mot de passe</Text>
      <TextInput
        testID="password"
        style={styles.input}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
      />
      <Text style={styles.hint}>Au moins {PASSWORD_MIN_LENGTH} caractères dont 1 chiffre.</Text>
      {errors.password ? <Text testID="password-error" style={styles.error}>{errors.password}</Text> : null}

      {serverError ? (
        <Text testID="server-error" accessibilityRole="alert" style={styles.error}>
          {serverError}
        </Text>
      ) : null}

      <Pressable
        testID="submit"
        accessibilityRole="button"
        style={[styles.button, submitting && styles.buttonDisabled]}
        onPress={submit}
        disabled={submitting}
      >
        {submitting ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Créer mon compte</Text>}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#fff' },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 24 },
  label: { marginTop: 12, marginBottom: 4, fontWeight: '600' },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 8, padding: 12, fontSize: 16 },
  hint: { fontSize: 12, color: '#666', marginTop: 4 },
  error: { color: '#c92a2a', marginTop: 4 },
  button: { marginTop: 24, backgroundColor: '#3b5bdb', borderRadius: 8, padding: 14, alignItems: 'center' },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
});
