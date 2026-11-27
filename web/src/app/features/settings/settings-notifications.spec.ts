import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Settings } from './settings';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { BROWSER_PUSH } from '../../core/notifications/browser-push';
import { FakeBrowserPush, settle } from '../../core/notifications/testing';
import {
  ENABLE_FAILED_MESSAGE,
  PERMISSION_DISMISSED_MESSAGE,
} from '../../core/notifications/web-push.service';

describe('Settings — notifications du navigateur', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let browser: FakeBrowserPush;
  let fixture: ReturnType<typeof TestBed.createComponent<Settings>>;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const render = async () => {
    await settle();
    fixture.detectChanges();
  };
  const serverKey = async (key = 'KEY') => {
    await settle();
    http.expectOne('api/push/web/public-key').flush({ publicKey: key });
    await settle();
  };

  const start = async () => {
    fixture = TestBed.createComponent(Settings);
    fixture.detectChanges();
    el = fixture.nativeElement;
    await render();
  };

  beforeEach(() => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } }),
    );
    browser = new FakeBrowserPush();
    TestBed.configureTestingModule({
      imports: [Settings],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: BROWSER_PUSH, useValue: browser },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('propose d’activer les notifications, demande l’autorisation puis enregistre l’abonnement', async () => {
    await start();
    expect(q('notifications-disabled')).not.toBeNull();
    expect(browser.permissionRequests).toBe(0); // jamais demandé sans clic

    q('notifications-enable')!.click();
    await serverKey('KEY');
    expect(browser.permissionRequests).toBe(1);
    expect(browser.subscribed).toEqual(['KEY']);

    const put = http.expectOne('api/push/web/subscription');
    expect(put.request.method).toBe('PUT');
    expect(put.request.headers.get('Authorization')).toBe('Bearer jwt');
    expect(put.request.body).toEqual({
      endpoint: 'https://fcm.googleapis.com/fcm/send/abc-1',
      keys: { p256dh: 'BPUB', auth: 'AUTH' },
    });
    put.flush(null, { status: 204, statusText: 'No Content' });
    await render();

    expect(q('notifications-enabled')!.textContent).toContain('Notifications activées');
    expect(q('notifications-disable')).not.toBeNull();
  });

  it('autorisation refusée : explique comment la réactiver, sans appel à l’API', async () => {
    browser.answer = 'denied';
    await start();
    q('notifications-enable')!.click();
    await render();

    const help = q('notifications-denied')!;
    expect(help.textContent).toContain('Les notifications sont bloquées');
    expect(help.textContent).toContain('« Autoriser »');
    expect(help.textContent).toContain('rechargez cette page');
    expect(q('notifications-enable')).toBeNull();
    expect(browser.subscribed).toEqual([]);
    http.expectNone('api/push/web/public-key');
  });

  it('autorisation déjà refusée à l’ouverture de la page : message d’aide affiché d’emblée', async () => {
    browser.perm = 'denied';
    await start();
    expect(q('notifications-denied')).not.toBeNull();
  });

  it('demande ignorée (fenêtre fermée) : invite à recommencer', async () => {
    browser.answer = 'default';
    await start();
    q('notifications-enable')!.click();
    await render();
    expect(q('notifications-notice')!.textContent).toContain(PERMISSION_DISMISSED_MESSAGE);
    expect(q('notifications-enable')).not.toBeNull();
  });

  it('déjà abonné : resynchronise l’abonnement puis permet de désactiver', async () => {
    browser.perm = 'granted';
    browser.sub = browser.subscription('https://fcm.googleapis.com/fcm/send/old', 'KEY');
    fixture = TestBed.createComponent(Settings);
    fixture.detectChanges();
    el = fixture.nativeElement;
    expect(q('notifications-checking')).not.toBeNull();

    await serverKey('KEY');
    const put = http.expectOne('api/push/web/subscription');
    expect(put.request.body.endpoint).toBe('https://fcm.googleapis.com/fcm/send/old');
    put.flush(null, { status: 204, statusText: 'No Content' });
    await render();
    expect(q('notifications-enabled')).not.toBeNull();
    expect(browser.subscribed).toEqual([]);

    q('notifications-disable')!.click();
    await settle();
    const del = http.expectOne('api/push/web/subscription');
    expect(del.request.method).toBe('DELETE');
    expect(del.request.body).toEqual({ endpoint: 'https://fcm.googleapis.com/fcm/send/old' });
    del.flush(null, { status: 204, statusText: 'No Content' });
    await render();
    expect(browser.sub).toBeNull();
    expect(q('notifications-disabled')).not.toBeNull();
  });

  it('clé du serveur changée : l’abonnement est recréé avec la nouvelle clé', async () => {
    browser.perm = 'granted';
    browser.sub = browser.subscription('https://fcm.googleapis.com/fcm/send/old', 'ANCIENNE');
    await start();
    await serverKey('NOUVELLE');
    expect(browser.subscribed).toEqual(['NOUVELLE']);
    const put = http.expectOne('api/push/web/subscription');
    expect(put.request.body.endpoint).toBe('https://fcm.googleapis.com/fcm/send/abc-1');
    put.flush(null, { status: 204, statusText: 'No Content' });
    await render();
    expect(q('notifications-enabled')).not.toBeNull();
  });

  it('échec de l’enregistrement : message d’erreur, notifications non activées', async () => {
    await start();
    q('notifications-enable')!.click();
    await serverKey();
    http
      .expectOne('api/push/web/subscription')
      .flush({ message: 'Erreur' }, { status: 500, statusText: 'Server Error' });
    await render();
    expect(q('notifications-notice')!.textContent).toContain(ENABLE_FAILED_MESSAGE);
    expect(q('notifications-enable')).not.toBeNull();
  });

  it('navigateur sans Web Push : le signale', async () => {
    browser.isSupported = false;
    await start();
    expect(q('notifications-unsupported')!.textContent).toContain('ne permet pas');
    expect(q('notifications-enable')).toBeNull();
  });
});
