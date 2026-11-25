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
