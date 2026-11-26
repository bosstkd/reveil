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

/** Message affiché quand l'événement visé a été supprimé entre-temps (404). */
export const EVENT_GONE = "Cet événement n'existe plus.";
/** Message affiché quand l'événement appartient à un autre utilisateur (403). */
export const EVENT_FORBIDDEN = "Vous n'êtes pas autorisé à modifier cet événement.";

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

/** Délai d'un rappel (« 1 h avant », « 30 min avant », « 1 h 30 min avant », « 2 jours avant »). */
export function reminderDelay(minutesBefore: number): string {
  if (minutesBefore <= 0) {
    return 'Au début';
  }
  if (minutesBefore % 1440 === 0) {
    const days = minutesBefore / 1440;
    return `${days} ${days > 1 ? 'jours' : 'jour'} avant`;
  }
  const h = Math.floor(minutesBefore / 60);
  const m = minutesBefore % 60;
  if (h === 0) {
    return `${m} min avant`;
  }
  return `${h} h${m ? ` ${m} min` : ''} avant`;
}

export const REMINDER_STATUS: Record<ReminderStatus, string> = {
  scheduled: 'programmé',
  sent: 'envoyé',
  skipped: 'non envoyé',
};

/** Échéance d'un rappel dans le fuseau de saisie de l'événement (ex. « 24 déc. 2099, 19:30 »). */
export function reminderAt(event: ReveilEvent, reminder: EventReminder): string {
  try {
    return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short', timeZone: event.timeZone }).format(
      new Date(reminder.remindAt),
    );
  } catch {
    return reminder.remindAt;
  }
}
