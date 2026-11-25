import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('appelle POST api/auth/register en chemin relatif et ouvre la session', () => {
    expect(service.isAuthenticated()).toBe(false);

    service.register('alice@example.com', 'motdepasse1').subscribe();

    const req = http.expectOne('api/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'alice@example.com', password: 'motdepasse1' });
    req.flush({ token: 'jwt', user: { id: 'u1', email: 'alice@example.com' } });

    expect(service.isAuthenticated()).toBe(true);
    expect(service.token).toBe('jwt');
    expect(service.currentUser()?.email).toBe('alice@example.com');
    const stored = localStorage.getItem('reveil.session') ?? '';
    expect(stored).toContain('jwt');
    expect(stored).not.toContain('motdepasse1');
  });

  it("n'ouvre pas de session en cas d'erreur", () => {
    service.register('alice@example.com', 'motdepasse1').subscribe({ error: () => undefined });
    http.expectOne('api/auth/register').flush({}, { status: 409, statusText: 'Conflict' });
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logout ferme la session', () => {
    service.register('a@b.co', 'motdepasse1').subscribe();
    http.expectOne('api/auth/register').flush({ token: 't', user: { id: '1', email: 'a@b.co' } });
    service.logout();
    expect(service.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('reveil.session')).toBeNull();
  });
});
