import { useState } from 'react';
import { ActivityIndicator, Pressable, Text, TextInput, View } from 'react-native';
import { AuthError, INVALID_RESET_LINK_MESSAGE, confirmPasswordReset } from '../auth/authApi';
import { extractResetToken } from '../auth/resetLink';
import { PASSWORD_MIN_LENGTH, validatePassword } from '../auth/validation';
import { styles } from './ForgotPasswordScreen';

/**
 * Saisie du nouveau mot de passe. Jeton fourni par le lien profond (`initialToken`) ou, à défaut, lien de
 * l'e-mail collé par l'utilisateur.
 */
export function ResetPasswordScreen({
  initialToken,
  onDone,
  onBack,
  onRequestNew,
}: {
  initialToken?: string | null;
  onDone: () => void;
  onBack: () => void;
  onRequestNew: () => void;
}) {
  const [link, setLink] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<{ link?: string | null; password?: string | null; confirm?: string | null }>({});
  const [error, setError] = useState<string | null>(null);
  const [linkInvalid, setLinkInvalid] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    const token = initialToken ?? extractResetToken(link);
    const next = {
      link: token ? null : 'Collez le lien reçu par e-mail.',
      password: validatePassword(password),
      confirm: !confirm
        ? 'Confirmez le nouveau mot de passe.'
        : confirm !== password
          ? 'Les deux mots de passe ne correspondent pas.'
          : null,
    };
    setErrors(next);
    if (!token || next.password || next.confirm || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await confirmPasswordReset(token, password);
      onDone();
    } catch (e) {
      setSubmitting(false);
      if (e instanceof AuthError && e.message === INVALID_RESET_LINK_MESSAGE) {
        setLinkInvalid(true);
      } else {
        setError(e instanceof AuthError ? e.message : 'La réinitialisation a échoué.');
      }
    }
  }

  if (linkInvalid) {
    return (
      <View style={styles.container}>
        <Text style={styles.title} accessibilityRole="header">
          Nouveau mot de passe
        </Text>
        <Text testID="reset-invalid-link" accessibilityRole="alert" style={styles.error}>
          {INVALID_RESET_LINK_MESSAGE}
        </Text>
        <Text style={styles.hint}>Un lien est valable 1 heure et ne peut servir qu'une seule fois.</Text>
        <Pressable testID="reset-request-new" accessibilityRole="button" style={styles.button} onPress={onRequestNew}>
          <Text style={styles.buttonText}>Demander un nouveau lien</Text>
        </Pressable>
        <Pressable testID="reset-back" accessibilityRole="link" onPress={onBack} style={styles.switch}>
          <Text style={styles.link}>Retour à la connexion</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        Nouveau mot de passe
      </Text>

      {initialToken ? null : (
        <>
          <Text style={styles.label}>Lien reçu par e-mail</Text>
          <TextInput
            testID="reset-link"
            style={styles.input}
            value={link}
            onChangeText={setLink}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="https://…/reinitialiser-mot-de-passe?token=…"
          />
          {errors.link ? <Text testID="reset-link-error" style={styles.error}>{errors.link}</Text> : null}
        </>
      )}

      <Text style={styles.label}>Nouveau mot de passe</Text>
      <TextInput
        testID="reset-password"
        style={styles.input}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
      />
      <Text style={styles.hint}>Au moins {PASSWORD_MIN_LENGTH} caractères dont 1 chiffre.</Text>
      {errors.password ? <Text testID="reset-password-error" style={styles.error}>{errors.password}</Text> : null}

      <Text style={styles.label}>Confirmer le mot de passe</Text>
      <TextInput
        testID="reset-confirm"
        style={styles.input}
        value={confirm}
        onChangeText={setConfirm}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
      />
      {errors.confirm ? <Text testID="reset-confirm-error" style={styles.error}>{errors.confirm}</Text> : null}

      {error ? (
        <Text testID="reset-error" accessibilityRole="alert" style={styles.error}>
          {error}
        </Text>
      ) : null}

      <Pressable
        testID="reset-submit"
        accessibilityRole="button"
        style={[styles.button, submitting && styles.buttonDisabled]}
        onPress={submit}
        disabled={submitting}
      >
        {submitting ? (
          <ActivityIndicator color="#fff" />
        ) : (
          <Text style={styles.buttonText}>Enregistrer le mot de passe</Text>
        )}
      </Pressable>

      <Pressable testID="reset-back" accessibilityRole="link" onPress={onBack} style={styles.switch}>
        <Text style={styles.link}>Retour à la connexion</Text>
      </Pressable>
    </View>
  );
}
