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

/** Onglet de la liste : à venir (date ≥ maintenant) ou passés. */
export type EventPeriod = 'upcoming' | 'past';

/** Page renvoyée par GET /api/events. */
export interface EventPage {
  when: EventPeriod;
  items: ReveilEvent[];
  page: number;
  size: number;
  totalElements: number;
  hasNext: boolean;
}

/** Taille de page demandée à l'API (maximum accepté : 100). */
export const PAGE_SIZE = 100;

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

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const UNITS: [number, string, string][] = [
  [365 * DAY, 'an', 'ans'],
  [30 * DAY, 'mois', 'mois'],
  [DAY, 'jour', 'jours'],
  [HOUR, 'heure', 'heures'],
  [MINUTE, 'minute', 'minutes'],
];

/**
 * Temps restant avant `startsAt` (« dans 2 jours », « dans 3 heures »), ou écoulé (« il y a 5 minutes »).
 * Arrondi à l'unité inférieure (mois = 30 jours, an = 365 jours) ; moins d'une minute : « maintenant ».
 */
export function timeUntil(startsAt: string, now: Date = new Date()): string {
  const diff = new Date(startsAt).getTime() - now.getTime();
  if (Number.isNaN(diff)) {
    return '';
  }
  const abs = Math.abs(diff);
  const unit = UNITS.find(([ms]) => abs >= ms);
  if (!unit) {
    return 'maintenant';
  }
  const n = Math.floor(abs / unit[0]);
  const label = `${n} ${n > 1 ? unit[2] : unit[1]}`;
  return diff > 0 ? `dans ${label}` : `il y a ${label}`;
}

function format(event: ReveilEvent, options: Intl.DateTimeFormatOptions): string | null {
  try {
    return new Intl.DateTimeFormat('fr-FR', { ...options, timeZone: event.timeZone }).format(new Date(event.startsAt));
  } catch {
    return null;
  }
}

/** Date de l'événement dans son fuseau de saisie (ex. « jeudi 24 décembre 2099 »). */
export function eventDate(event: ReveilEvent): string {
  return format(event, { dateStyle: 'full' }) ?? event.localDateTime.slice(0, 10);
}

/** Heure de l'événement dans son fuseau de saisie (ex. « 20:30 »). */
export function eventTime(event: ReveilEvent): string {
  return format(event, { timeStyle: 'short' }) ?? event.localDateTime.slice(11, 16);
}
