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

/** À venir : du plus proche au plus lointain ; passés : du plus récent au plus ancien. */
export function sortForPeriod(period: EventPeriod, events: ReveilEvent[]): ReveilEvent[] {
  const sign = period === 'upcoming' ? 1 : -1;
  return [...events].sort((a, b) => sign * a.startsAt.localeCompare(b.startsAt));
}

/** Fusionne `extra` dans `base` (remplacement par id) puis trie selon l'onglet. */
export function mergeEvents(period: EventPeriod, base: ReveilEvent[], extra: ReveilEvent[]): ReveilEvent[] {
  const ids = new Set(extra.map((e) => e.id));
  return sortForPeriod(period, [...base.filter((e) => !ids.has(e.id)), ...extra]);
}

function formatIn(event: ReveilEvent, options: Intl.DateTimeFormatOptions): string | null {
  try {
    return new Intl.DateTimeFormat('fr-FR', { ...options, timeZone: event.timeZone }).format(new Date(event.startsAt));
  } catch {
    return null;
  }
}

/** Date de l'événement dans son fuseau de saisie (ex. « jeudi 24 décembre 2099 »). */
export function eventDate(event: ReveilEvent): string {
  return formatIn(event, { dateStyle: 'full' }) ?? event.localDateTime.slice(0, 10);
}

/** Heure de l'événement dans son fuseau de saisie (ex. « 20:30 »). */
export function eventTime(event: ReveilEvent): string {
  return formatIn(event, { timeStyle: 'short' }) ?? event.localDateTime.slice(11, 16);
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
  if (Number.isNaN(diff)) return '';
  const abs = Math.abs(diff);
  const unit = UNITS.find(([ms]) => abs >= ms);
  if (!unit) return 'maintenant';
  const n = Math.floor(abs / unit[0]);
  const label = `${n} ${n > 1 ? unit[2] : unit[1]}`;
  return diff > 0 ? `dans ${label}` : `il y a ${label}`;
}
