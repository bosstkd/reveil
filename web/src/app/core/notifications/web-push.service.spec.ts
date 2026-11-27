import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BROWSER_PUSH, base64UrlToBytes, bytesToBase64Url } from './browser-push';
import { FakeBrowserPush } from './testing';
import { WebPushService } from './web-push.service';

describe('WebPushService', () => {
  let browser: FakeBrowserPush;

  beforeEach(() => {
    browser = new FakeBrowserPush();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BROWSER_PUSH, useValue: browser }],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('à la déconnexion, désabonne le navigateur sans appel à l’API', async () => {
    browser.sub = browser.subscription('https://fcm.googleapis.com/fcm/send/x', 'KEY');
    await TestBed.inject(WebPushService).forgetBrowser();
    expect(browser.sub).toBeNull();
  });

  it('navigateur non pris en charge : rien à faire', async () => {
    browser.isSupported = false;
    const service = TestBed.inject(WebPushService);
    await service.forgetBrowser();
    await service.enable();
    expect(browser.permissionRequests).toBe(0);
  });

  it('convertit la clé VAPID base64url en octets et inversement', () => {
    const key = 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8';
    const bytes = base64UrlToBytes(key);
    expect(bytes.length).toBe(65);
    expect(bytes[0]).toBe(4);
    expect(bytesToBase64Url(bytes)).toBe(key);
  });
});
