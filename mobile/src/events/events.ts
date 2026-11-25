/** Événement tel que renvoyé par l'API. */
export interface ReveilEvent {
  id: string;
  title: string;
  /** Instant UTC (ISO-8601, suffixe Z). */
  startsAt: string;
  /** Date/heure locale saisie, dans `timeZone`. */
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

const pad = (n: number) => String(n).padStart(2, '0');

/** Fuseau IANA de l'appareil (ex. « Europe/Paris »), UTC à défaut. */
export function deviceTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** Date locale de l'appareil au format AAAA-MM-JJ. */
export function formatDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Heure locale de l'appareil au format HH:mm. */
export function formatTime(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Combine le jour de `date` et l'heure de `time` (heure locale de l'appareil). */
export function combine(date: Date, time: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate(), time.getHours(), time.getMinutes());
}

export function isPast(date: Date | null, time: Date | null, now: Date = new Date()): boolean {
  return !!date && !!time && combine(date, time).getTime() < now.getTime();
}

/** Date/heure de l'événement dans son fuseau de saisie. */
export function formatWhen(event: ReveilEvent): string {
  try {
    return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full', timeStyle: 'short', timeZone: event.timeZone }).format(
      new Date(event.startsAt),
    );
  } catch {
    return event.localDateTime.replace('T', ' ').slice(0, 16);
  }
}

export const sortByStart = (events: ReveilEvent[]) => [...events].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
