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
  /** Rappels configurés (programmés ou déjà traités). */
  reminders: EventReminder[];
}

/** Statut d'un rappel : programmé, envoyé, ou abandonné (l'événement avait déjà commencé). */
export type ReminderStatus = 'scheduled' | 'sent' | 'skipped';

/** Rappel configuré d'un événement, tel que renvoyé par l'API. */
export interface EventReminder {
  /** Délai entre le rappel et le début de l'événement, en minutes. */
  minutesBefore: number;
  /** Échéance du rappel (instant UTC, ISO-8601). */
  remindAt: string;
  status: ReminderStatus;
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

/** Modification (PUT /api/events/{id}) : toutes les informations saisies à la création. */
export type UpdateEventPayload = CreateEventPayload;

export const TITLE_MAX_LENGTH = 200;
export const LOCATION_MAX_LENGTH = 300;
export const PAST_DATE_WARNING = "Cette date est déjà passée : l'événement sera tout de même créé.";
export const PAST_DATE_WARNING_EDIT = "Cette date est déjà passée : la modification sera tout de même enregistrée.";

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

/**
 * Date et heure saisies d'un événement (`localDateTime`, ex. 2099-12-24T20:30:00) en valeurs pour les
 * sélecteurs : les composantes sont reprises telles quelles (pas de conversion de fuseau).
 */
export function parseLocalDateTime(localDateTime: string): { date: Date; time: Date } {
  const [y, mo, d, h, mi] = localDateTime.split(/[-T:]/).map(Number);
  return { date: new Date(y, mo - 1, d), time: new Date(y, mo - 1, d, h, mi) };
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

/** Délai d'un rappel (« 1 h avant », « 30 min avant », « 1 h 30 min avant », « 2 jours avant »). */
export function reminderDelay(minutesBefore: number): string {
  if (minutesBefore <= 0) return 'Au début';
  if (minutesBefore % 1440 === 0) {
    const days = minutesBefore / 1440;
    return `${days} ${days > 1 ? 'jours' : 'jour'} avant`;
  }
  const h = Math.floor(minutesBefore / 60);
  const m = minutesBefore % 60;
  if (h === 0) return `${m} min avant`;
  return `${h} h${m ? ` ${m} min` : ''} avant`;
}

export const REMINDER_STATUS: Record<ReminderStatus, string> = {
  scheduled: 'programmé',
  sent: 'envoyé',
  skipped: 'non envoyé',
};

/**
 * Libellé complet d'un rappel, heure dans le fuseau de saisie de l'événement :
 * « 1 h avant · 24 déc. 2099, 19:30 · programmé ».
 */
export function reminderLabel(event: ReveilEvent, reminder: EventReminder): string {
  let at: string;
  try {
    at = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short', timeZone: event.timeZone }).format(
      new Date(reminder.remindAt),
    );
  } catch {
    at = reminder.remindAt;
  }
  return `${reminderDelay(reminder.minutesBefore)} · ${at} · ${REMINDER_STATUS[reminder.status] ?? reminder.status}`;
}
