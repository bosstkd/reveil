/** Événement tel que renvoyé par l'API. */
export interface ReveilEvent {
  id: string;
  title: string;
  /** Instant UTC (ISO-8601, suffixe Z). */
  startsAt: string;
  /** Date/heure locale saisie, dans `timeZone` (ex. 2026-12-24T20:30:00). */
  localDateTime: string;
  /** Fuseau IANA de l'utilisateur à la création. */
  timeZone: string;
  location: string | null;
  past: boolean;
  warnings: string[];
}

export interface CreateEventPayload {
  title: string;
  /** AAAA-MM-JJ */
  date: string;
  /** HH:mm */
  time: string;
  timeZone: string;
  location: string | null;
}

export const TITLE_MAX_LENGTH = 200;
export const LOCATION_MAX_LENGTH = 300;
export const PAST_DATE_WARNING = "Cette date est déjà passée : l'événement sera tout de même créé.";

/** Fuseau IANA du navigateur (ex. « Europe/Paris »), UTC à défaut. */
export function userTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** Vrai si la date/heure locale (fuseau du navigateur) est antérieure à `now`. */
export function isPast(date: string, time: string, now: Date = new Date()): boolean {
  if (!date || !time) {
    return false;
  }
  const local = new Date(`${date}T${time}`);
  return !Number.isNaN(local.getTime()) && local.getTime() < now.getTime();
}
