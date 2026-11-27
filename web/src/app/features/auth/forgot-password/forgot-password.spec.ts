import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ForgotPassword } from './forgot-password';
import { routes } from '../../../app.routes';
import { authInterceptor } from '../../../core/auth/auth.interceptor';
import { RESET_REQUESTED_MESSAGE } from '../../../core/auth/auth.models';

describe('ForgotPassword', () => {
  let fixture: ComponentFixture<ForgotPassword>;
  let http: HttpTestingController;
  let el: HTMLElement;

  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  function send(email: string) {
    const input = q('email') as HTMLInputElement;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    q('submit')!.click();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ForgotPassword],
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ForgotPassword);
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('envoie l’e-mail normalisé, sans jeton d’accès, puis affiche la confirmation', () => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'a@b.fr' } }),
    );
    send('  alice@example.com ');
    const req = http.expectOne('api/auth/password-reset/request');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'alice@example.com' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ message: RESET_REQUESTED_MESSAGE }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();

    expect(q('reset-requested')?.textContent?.trim()).toBe(RESET_REQUESTED_MESSAGE);
    expect(q('email')).toBeNull();
  });

  it('même confirmation pour un e-mail inconnu (réponse identique de l’API)', () => {
    send('personne@example.com');
    http
      .expectOne('api/auth/password-reset/request')
      .flush({ message: RESET_REQUESTED_MESSAGE }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect(q('reset-requested')?.textContent?.trim()).toBe(RESET_REQUESTED_MESSAGE);
  });

  it('e-mail invalide : erreur de champ, sans appel à l’API', () => {
    send('pas-un-email');
    expect(q('email-error')?.textContent).toContain('Saisissez un e-mail valide.');
    http.expectNone('api/auth/password-reset/request');
  });

  it('erreur serveur : message d’échec, formulaire conservé', () => {
    send('alice@example.com');
    http.expectOne('api/auth/password-reset/request').flush(null, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(q('server-error')?.textContent).toContain("n'a pas pu être envoyée");
    expect(q('reset-requested')).toBeNull();
    expect((q('submit') as HTMLButtonElement).disabled).toBe(false);
  });
});
