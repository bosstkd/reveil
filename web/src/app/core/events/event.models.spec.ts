import { ReveilEvent, eventDate, eventTime, isPast, timeUntil, userTimeZone } from './event.models';

describe('event.models', () => {
  const now = new Date(2026, 10, 25, 12, 0); // 25/11/2026 12:00, heure locale

  it('isPast compare la date/heure locale à maintenant', () => {
    expect(isPast('2026-11-25', '11:59', now)).toBe(true);
    expect(isPast('2026-11-25', '12:01', now)).toBe(false);
    expect(isPast('', '12:00', now)).toBe(false);
    expect(isPast('2026-11-25', '', now)).toBe(false);
  });

  it('userTimeZone renvoie un fuseau IANA', () => {
    expect(userTimeZone()).toMatch(/\w/);
  });

  describe('timeUntil', () => {
    const ref = new Date('2026-11-25T12:00:00Z');
    const at = (ms: number) => new Date(ref.getTime() + ms).toISOString();
    const MIN = 60_000;
    const HOUR = 60 * MIN;
    const DAY = 24 * HOUR;

    it.each([
      [30_000, 'maintenant'],
      [-30_000, 'maintenant'],
      [MIN, 'dans 1 minute'],
      [45 * MIN, 'dans 45 minutes'],
      [HOUR, 'dans 1 heure'],
      [5 * HOUR + 59 * MIN, 'dans 5 heures'],
      [DAY, 'dans 1 jour'],
      [2 * DAY + HOUR, 'dans 2 jours'],
      [45 * DAY, 'dans 1 mois'],
      [400 * DAY, 'dans 1 an'],
      [800 * DAY, 'dans 2 ans'],
      [-3 * HOUR, 'il y a 3 heures'],
      [-DAY, 'il y a 1 jour'],
    ])('%d ms → %s', (delta, expected) => {
      expect(timeUntil(at(delta), ref)).toBe(expected);
    });

    it('date illisible : chaîne vide', () => {
      expect(timeUntil('n/a', ref)).toBe('');
    });
  });

  it('date et heure affichées dans le fuseau de saisie', () => {
    const e = {
      startsAt: '2026-12-24T19:30:00Z',
      localDateTime: '2026-12-24T20:30:00',
      timeZone: 'Europe/Paris',
    } as ReveilEvent;
    expect(eventDate(e)).toBe('jeudi 24 décembre 2026');
    expect(eventTime(e)).toBe('20:30');
    expect(eventTime({ ...e, timeZone: 'America/New_York' })).toBe('14:30');
    // Fuseau inconnu du navigateur : repli sur l'heure locale renvoyée par l'API.
    expect(eventTime({ ...e, timeZone: 'Mars/Olympus' })).toBe('20:30');
    expect(eventDate({ ...e, timeZone: 'Mars/Olympus' })).toBe('2026-12-24');
  });
});
