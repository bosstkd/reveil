import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Service worker `public/reveil-sw.js`, exécuté avec un environnement de service worker simulé. */
describe('Service worker reveil-sw.js', () => {
  const SCOPE = 'https://hote.example/test/reveil/';
  let handlers: Record<string, (event: unknown) => void>;
  let showNotification: ReturnType<typeof vi.fn>;
  let openWindow: ReturnType<typeof vi.fn>;
  let windows: { url: string; focus: ReturnType<typeof vi.fn>; navigate: ReturnType<typeof vi.fn> }[];

  const dispatch = async (type: string, event: Record<string, unknown>) => {
    let pending: Promise<unknown> = Promise.resolve();
    handlers[type]({ ...event, waitUntil: (p: Promise<unknown>) => (pending = p) });
    await pending;
  };
  const pushData = (payload: unknown) => ({ json: () => payload, text: () => JSON.stringify(payload) });

  beforeEach(() => {
    handlers = {};
    windows = [];
    showNotification = vi.fn().mockResolvedValue(undefined);
    openWindow = vi.fn().mockResolvedValue(null);
    const self = {
      registration: { scope: SCOPE, showNotification },
      clients: { claim: vi.fn(), matchAll: vi.fn(async () => windows), openWindow },
      skipWaiting: vi.fn(),
      addEventListener: (type: string, handler: (event: unknown) => void) => (handlers[type] = handler),
    };
    const code = readFileSync(resolve(process.cwd(), 'public/reveil-sw.js'), 'utf8');
    new Function('self', code)(self);
  });

  it('affiche le rappel reçu en notification système', async () => {
    await dispatch('push', {
      data: pushData({
        title: 'Rappel : Concert',
        body: 'Commence dans 1 h, à 20:30 · Olympia',
        url: 'evenements/e1',
        tag: 'reminder-e1',
        data: { type: 'event-reminder', eventId: 'e1' },
      }),
    });
    expect(showNotification).toHaveBeenCalledWith(
      'Rappel : Concert',
      expect.objectContaining({
        body: 'Commence dans 1 h, à 20:30 · Olympia',
        tag: 'reminder-e1',
        icon: SCOPE + 'favicon.ico',
        data: expect.objectContaining({ url: SCOPE + 'evenements/e1', eventId: 'e1' }),
      }),
    );
  });

  it('contenu illisible ou lien externe : notification par défaut, lien ramené à l’application', async () => {
    await dispatch('push', { data: pushData({ url: 'https://pirate.example/' }) });
    expect(showNotification).toHaveBeenCalledWith('Reveil', expect.objectContaining({ data: { url: SCOPE } }));
  });

  it('au clic : ouvre l’événement dans un nouvel onglet si aucun n’est ouvert', async () => {
    const close = vi.fn();
    await dispatch('notificationclick', { notification: { close, data: { url: SCOPE + 'evenements/e1' } } });
    expect(close).toHaveBeenCalled();
    expect(openWindow).toHaveBeenCalledWith(SCOPE + 'evenements/e1');
  });

  it('au clic : réutilise un onglet de l’application déjà ouvert', async () => {
    const tab = { url: SCOPE + 'evenements', focus: vi.fn(), navigate: vi.fn().mockResolvedValue(null) };
    tab.focus.mockResolvedValue(tab);
    windows = [{ url: 'https://autre.example/', focus: vi.fn(), navigate: vi.fn() }, tab];
    await dispatch('notificationclick', { notification: { close: vi.fn(), data: { url: SCOPE + 'evenements/e1' } } });
    expect(tab.focus).toHaveBeenCalled();
    expect(tab.navigate).toHaveBeenCalledWith(SCOPE + 'evenements/e1');
    expect(openWindow).not.toHaveBeenCalled();
  });
});
