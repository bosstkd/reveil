export interface AuthUser {
  id: string;
  email: string;
}

export interface AuthResponse {
  /** Jeton d'accès JWT (courte durée). */
  token: string;
  /** Validité du jeton d'accès, en secondes. */
  expiresIn: number;
  /** Jeton de rafraîchissement (rotation à chaque usage). */
  refreshToken: string;
  user: AuthUser;
}

export interface ApiError {
  message: string;
  errors?: { field: string; message: string }[];
}

/** Même règle que le backend : partie locale, @, domaine avec un point. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_DIGIT_PATTERN = /\d/;
export const EMAIL_ALREADY_USED_MESSAGE = 'Un compte existe déjà avec cet e-mail';
/** Message générique : ne précise jamais le champ en cause. */
export const INVALID_CREDENTIALS_MESSAGE = 'E-mail ou mot de passe incorrect';
/** Confirmation de demande de réinitialisation : identique que le compte existe ou non (même texte que l'API). */
export const RESET_REQUESTED_MESSAGE =
  'Si un compte est associé à cet e-mail, un lien de réinitialisation vient de lui être envoyé. Il est valable 1 heure.';
/** Lien inconnu, expiré (1 h) ou déjà utilisé (même texte que l'API). */
export const INVALID_RESET_LINK_MESSAGE = 'Ce lien de réinitialisation est invalide ou a expiré';
