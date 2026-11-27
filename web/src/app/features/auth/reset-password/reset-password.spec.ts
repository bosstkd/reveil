import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';
import { authInterceptor } from '../../../core/auth/auth.interceptor';
import { INVALID_RESET_LINK_MESSAGE } from '../../../core/auth/auth.models';

describe('ResetPassword', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let router: Router;

  const q = (id: string) => harness.routeNativeElement!.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const type = (id: string, value: string) => {
    const input = q(id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const submit = (password: string, confirm = password) => {
    type('password', password);
    type('confirm', confirm);
    q('submit')!.click();
    harness.detectChanges();
  };

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create('/reinitialiser-mot-de-passe?token=jeton-abc');
  });

  afterEach(() => http.verify());

  it('retire le jeton de la barre d’adresse', () => {
    expect(router.url).toBe('/reinitialiser-mot-de-passe');
    expect(q('password')).not.toBeNull();
  });

  it('nouveau mot de passe valide : envoie le jeton, ferme la session locale et renvoie vers la connexion', async () => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'a@b.fr' } }),
    );
    submit('nouveau456');
    const req = http.expectOne('api/auth/password-reset/confirm');
    expect(req.request.body).toEqual({ token: 'jeton-abc', password: 'nouveau456' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(localStorage.getItem('reveil.session')).toBeNull();
    expect(router.url).toBe('/connexion?motdepasse=reinitialise');
    expect(q('password-reset-done')?.textContent).toContain('Connectez-vous avec le nouveau mot de passe');
  });

  it('règles du mot de passe et confirmation vérifiées avant tout appel', () => {
    submit('court');
    expect(q('password-error')?.textContent).toContain('au moins 8 caractères');
    submit('sanschiffre');
    expect(q('password-error')?.textContent).toContain('1 chiffre');
    submit('nouveau456', 'nouveau457');
    expect(q('confirm-error')?.textContent).toContain('ne correspondent pas');
    http.expectNone('api/auth/password-reset/confirm');
  });

  it('lien expiré ou déjà utilisé : message et proposition d’un nouveau lien', () => {
    submit('nouveau456');
    http
      .expectOne('api/auth/password-reset/confirm')
      .flush(
        { message: INVALID_RESET_LINK_MESSAGE, errors: [{ field: 'token', message: INVALID_RESET_LINK_MESSAGE }] },
        { status: 400, statusText: 'Bad Request' },
      );
    harness.detectChanges();

    expect(q('invalid-link')?.textContent).toContain(INVALID_RESET_LINK_MESSAGE);
    expect(q('to-forgot-password')?.getAttribute('href')).toBe('/mot-de-passe-oublie');
    expect(q('password')).toBeNull();
  });

  it('mot de passe refusé par l’API : message du champ', () => {
    submit('nouveau456');
    http
      .expectOne('api/auth/password-reset/confirm')
      .flush(
        { message: 'Données invalides', errors: [{ field: 'password', message: 'Mot de passe trop long' }] },
        { status: 400, statusText: 'Bad Request' },
      );
    harness.detectChanges();
    expect(q('server-error')?.textContent).toContain('Mot de passe trop long');
  });

  it('sans jeton dans le lien : directement le message de lien invalide', async () => {
    await harness.navigateByUrl('/connexion');
    await harness.navigateByUrl('/reinitialiser-mot-de-passe');
    expect(q('invalid-link')).not.toBeNull();
  });
});
