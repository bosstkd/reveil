import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { Register } from './register';
import { routes } from '../../../app.routes';

describe('Register', () => {
  let fixture: ComponentFixture<Register>;
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
      imports: [Register],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Register);
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it.each(['', 'alice', 'alice@', 'alice@example', 'a b@example.com'])(
    "refuse l'e-mail invalide « %s » sans appeler l'API",
    (email) => {
      fill(email, 'motdepasse1');
      expect(q('email-error')).not.toBeNull();
      http.expectNone('api/auth/register');
    },
  );

  it('exige au moins 8 caractères', () => {
    fill('alice@example.com', 'abc1234');
    expect(q('password-error')?.textContent).toContain('au moins 8 caractères');
    http.expectNone('api/auth/register');
  });

  it('exige au moins 1 chiffre', () => {
    fill('alice@example.com', 'sanschiffre');
    expect(q('password-error')?.textContent).toContain('1 chiffre');
    http.expectNone('api/auth/register');
  });

  it("connecte l'utilisateur et redirige vers ses événements après inscription", async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl');
    fill('alice@example.com', 'abcdefg1');

    const req = http.expectOne('api/auth/register');
    expect(req.request.body).toEqual({ email: 'alice@example.com', password: 'abcdefg1' });
    req.flush({ token: 'jwt', expiresIn: 900, refreshToken: 's.r', user: { id: 'u1', email: 'alice@example.com' } }, { status: 201, statusText: 'Created' });

    expect(navigate).toHaveBeenCalledWith('/evenements');
    expect(localStorage.getItem('reveil.session')).toContain('jwt');
  });

  it('affiche « Un compte existe déjà avec cet e-mail » si l’e-mail est déjà utilisé', () => {
    fill('alice@example.com', 'motdepasse1');
    http
      .expectOne('api/auth/register')
      .flush({ message: 'Un compte existe déjà avec cet e-mail' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(q('server-error')?.textContent).toContain('Un compte existe déjà avec cet e-mail');
    expect((q('submit') as HTMLButtonElement).disabled).toBe(false);
  });

  it('affiche un message générique sur erreur serveur', () => {
    fill('alice@example.com', 'motdepasse1');
    http.expectOne('api/auth/register').flush(null, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(q('server-error')?.textContent).toContain('échoué');
  });
});
