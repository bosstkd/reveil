import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  function run() {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient()] });
    return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));
  }

  it("redirige vers /connexion sans session", () => {
    const result = run() as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/connexion');
  });

  it('ignore une session stockée incomplète (sans jeton de rafraîchissement)', () => {
    localStorage.setItem('reveil.session', JSON.stringify({ token: 't', user: { id: '1', email: 'a@b.co' } }));
    expect(run()).not.toBe(true);
  });

  it('laisse passer avec une session', () => {
    localStorage.setItem('reveil.session', JSON.stringify({ token: 't', refreshToken: 's.r', user: { id: '1', email: 'a@b.co' } }));
    expect(run()).toBe(true);
  });
});
