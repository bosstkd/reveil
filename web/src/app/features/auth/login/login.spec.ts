import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Login } from './login';
import { routes } from '../../../app.routes';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let http: HttpTestingController;
  let router: Router;
  let el: HTMLElement;

  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  function fill(email: string, password: string) {
    const e = q('email') as HTMLInputElement;
    const p = q('password') as HTMLInputElement;
    e.value = email;
    e.dispatchEvent(new Event('input'));
    p.value = password;
    p.dispatchEvent(new Event('input'));
    q('submit')!.click();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Login);
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('identifiants valides : ouvre la session et redirige vers les événements', () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fill(' alice@example.com ', 'motdepasse1');

    const req = http.expectOne('api/auth/login');
    expect(req.request.body).toEqual({ email: 'alice@example.com', password: 'motdepasse1' });
    req.flush({ token: 'jwt', expiresIn: 900, refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } });

    expect(navigate).toHaveBeenCalledWith('/evenements');
    expect(localStorage.getItem('reveil.session')).toContain('jwt');
  });

  it('identifiants invalides : message générique, sans indiquer le champ en cause', () => {
    fill('alice@example.com', 'mauvais');
    http
      .expectOne('api/auth/login')
      .flush({ message: 'E-mail ou mot de passe incorrect', errors: [] }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(q('login-error')?.textContent?.trim()).toBe('E-mail ou mot de passe incorrect');
    expect(el.querySelector('.field-error')).toBeNull();
    expect((q('password') as HTMLInputElement).value).toBe('');
    expect((q('submit') as HTMLButtonElement).disabled).toBe(false);
  });

  it('champs vides : même message générique, sans appel à l’API', () => {
    fill('', '');
    expect(q('login-error')?.textContent?.trim()).toBe('E-mail ou mot de passe incorrect');
    http.expectNone('api/auth/login');
  });

  it('erreur serveur : message distinct', () => {
    fill('alice@example.com', 'motdepasse1');
    http.expectOne('api/auth/login').flush(null, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(q('login-error')?.textContent).toContain('échoué');
  });

  it('propose un lien vers l’inscription', () => {
    expect(q('to-register')?.getAttribute('href')).toBe('/inscription');
  });

  it('n’affiche pas de confirmation de suppression de compte par défaut', () => {
    expect(q('account-deleted')).toBeNull();
  });

  it('après la suppression du compte : confirmation affichée', async () => {
    const harness = await RouterTestingHarness.create('/connexion?compte=supprime');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('[data-testid="account-deleted"]')?.textContent).toContain(
      'Votre compte et toutes vos données ont été supprimés.',
    );
  });
});
