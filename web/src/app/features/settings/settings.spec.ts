import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { Settings } from './settings';
import { AuthService } from '../../core/auth/auth.service';
import { authInterceptor } from '../../core/auth/auth.interceptor';

describe('Settings — suppression du compte', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<Settings>>;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const render = () => fixture.detectChanges();
  const type = (id: string, value: string) => {
    const input = q<HTMLInputElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

  beforeEach(() => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } }),
    );
    TestBed.configureTestingModule({
      imports: [Settings],
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Settings);
    render();
    el = fixture.nativeElement;
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('affiche le compte et ne demande le mot de passe qu’après le choix de supprimer', () => {
    expect(el.textContent).toContain('alice@example.com');
    expect(q('delete-form')).toBeNull();
    q('delete-account')!.click();
    render();
    expect(q('delete-password')).not.toBeNull();
    expect(q('delete-password')!.getAttribute('type')).toBe('password');
  });

  it('mot de passe vide : aucun appel à l’API', () => {
    q('delete-account')!.click();
    render();
    q('delete-confirm')!.click();
    render();
    expect(q('delete-error')!.textContent).toContain('Saisissez votre mot de passe');
    http.expectNone('api/account');
  });

  it('confirme par mot de passe, supprime le compte, ferme la session et renvoie vers la connexion', () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    q('delete-account')!.click();
    render();
    type('delete-password', 'secret123');
    q('delete-confirm')!.click();
    render();
    expect(q<HTMLButtonElement>('delete-confirm')!.disabled).toBe(true);

    const req = http.expectOne('api/account');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.body).toEqual({ password: 'secret123' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(localStorage.getItem('reveil.session')).toBeNull();
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/connexion?compte=supprime');
  });

  it('mot de passe refusé (403) : message, session conservée, aucun rafraîchissement', () => {
    q('delete-account')!.click();
    render();
    type('delete-password', 'mauvais1');
    q('delete-confirm')!.click();
    http
      .expectOne('api/account')
      .flush(
        { message: 'Mot de passe incorrect', errors: [{ field: 'password', message: 'Mot de passe incorrect' }] },
        { status: 403, statusText: 'Forbidden' },
      );
    render();

    expect(q('delete-error')!.textContent).toContain('Mot de passe incorrect');
    expect(q<HTMLInputElement>('delete-password')!.value).toBe('');
    expect(q<HTMLButtonElement>('delete-confirm')!.disabled).toBe(false);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
    http.expectNone('api/auth/refresh');
  });

  it('erreur serveur : message générique, compte conservé', () => {
    q('delete-account')!.click();
    render();
    type('delete-password', 'secret123');
    q('delete-confirm')!.click();
    http.expectOne('api/account').flush(null, { status: 500, statusText: 'Erreur' });
    render();
    expect(q('delete-error')!.textContent).toContain('La suppression a échoué');
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  it('annuler masque le formulaire', () => {
    q('delete-account')!.click();
    render();
    q('delete-cancel')!.click();
    render();
    expect(q('delete-form')).toBeNull();
    http.expectNone('api/account');
  });
});
