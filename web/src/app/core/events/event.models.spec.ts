import { isPast, userTimeZone } from './event.models';

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
});
