/** Mêmes règles que le backend et le web. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const PASSWORD_MIN_LENGTH = 8;
export const EMAIL_ALREADY_USED_MESSAGE = 'Un compte existe déjà avec cet e-mail';
/** Message générique de connexion refusée : ne précise jamais le champ en cause. */
export const INVALID_CREDENTIALS_MESSAGE = 'E-mail ou mot de passe incorrect';

export function validateEmail(email: string): string | null {
  const value = email.trim();
  if (!value) return "L'e-mail est obligatoire.";
  if (!EMAIL_PATTERN.test(value)) return 'Saisissez un e-mail valide.';
  return null;
}

export function validatePassword(password: string): string | null {
  if (!password) return 'Le mot de passe est obligatoire.';
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `Le mot de passe doit contenir au moins ${PASSWORD_MIN_LENGTH} caractères.`;
  }
  if (!/\d/.test(password)) return 'Le mot de passe doit contenir au moins 1 chiffre.';
  return null;
}
