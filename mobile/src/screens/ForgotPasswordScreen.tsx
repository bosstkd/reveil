import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { AuthError, RESET_REQUESTED_MESSAGE, requestPasswordReset } from '../auth/authApi';
import { validateEmail } from '../auth/validation';

/** Demande d'un lien de réinitialisation. La confirmation ne révèle pas si le compte existe. */
export function ForgotPasswordScreen({ onBack, onHaveLink }: { onBack: () => void; onHaveLink: () => void }) {
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    const invalid = validateEmail(email);
    setEmailError(invalid);
    if (invalid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await requestPasswordReset(email.trim());
      setSent(true);
    } catch (e) {
      setError(e instanceof AuthError ? e.message : "La demande n'a pas pu être envoyée.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        Mot de passe oublié
      </Text>

      {sent ? (
        <>
          <Text testID="reset-requested" accessibilityRole="alert" style={styles.notice}>
            {RESET_REQUESTED_MESSAGE}
          </Text>
          <Text style={styles.hint}>Touchez le lien de l'e-mail sur ce téléphone, ou collez-le ici.</Text>
          <Pressable testID="to-reset-password" accessibilityRole="button" style={styles.button} onPress={onHaveLink}>
            <Text style={styles.buttonText}>J'ai reçu le lien</Text>
          </Pressable>
        </>
      ) : (
        <>
          <Text style={styles.hint}>
            Saisissez l'e-mail de votre compte : nous vous enverrons un lien pour choisir un nouveau mot de passe.
          </Text>
          <Text style={styles.label}>E-mail</Text>
          <TextInput
            testID="forgot-email"
            style={styles.input}
            value={email}
            onChangeText={setEmail}
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            textContentType="emailAddress"
            placeholder="vous@exemple.fr"
          />
          {emailError ? <Text testID="forgot-email-error" style={styles.error}>{emailError}</Text> : null}
          {error ? (
            <Text testID="forgot-error" accessibilityRole="alert" style={styles.error}>
              {error}
            </Text>
          ) : null}
          <Pressable
            testID="forgot-submit"
            accessibilityRole="button"
            style={[styles.button, submitting && styles.buttonDisabled]}
            onPress={submit}
            disabled={submitting}
          >
            {submitting ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Envoyer le lien</Text>}
          </Pressable>
        </>
      )}

      <Pressable testID="forgot-back" accessibilityRole="link" onPress={onBack} style={styles.switch}>
        <Text style={styles.link}>Retour à la connexion</Text>
      </Pressable>
    </View>
  );
}

export const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#fff' },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 24 },
  label: { marginTop: 12, marginBottom: 4, fontWeight: '600' },
  hint: { color: '#666', marginTop: 4 },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 8, padding: 12, fontSize: 16 },
  error: { color: '#c92a2a', marginTop: 8 },
  notice: { padding: 10, borderRadius: 8, backgroundColor: '#ebfbee', color: '#2b8a3e' },
  button: { marginTop: 24, backgroundColor: '#3b5bdb', borderRadius: 8, padding: 14, alignItems: 'center' },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  switch: { marginTop: 20, alignItems: 'center' },
  link: { color: '#3b5bdb' },
});
