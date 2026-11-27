import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

const response = (token = 'jwt', refreshToken = 's.r1') => ({
  token,
  expiresIn: 900,
  refreshToken,
  user: { id: 'u1', email: 'alice@example.com' },
});

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
    req.flush(response());

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

  it('login : POST api/auth/login, stocke jeton d’accès et de rafraîchissement, jamais le mot de passe', () => {
    service.login('alice@example.com', 'motdepasse1').subscribe();
    const req = http.expectOne('api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'alice@example.com', password: 'motdepasse1' });
    req.flush(response());

    expect(service.isAuthenticated()).toBe(true);
    const stored = JSON.parse(localStorage.getItem('reveil.session') ?? '{}');
    expect(stored.token).toBe('jwt');
    expect(stored.refreshToken).toBe('s.r1');
    expect(JSON.stringify(stored)).not.toContain('motdepasse1');
  });

  it('login refusé : pas de session', () => {
    service.login('alice@example.com', 'x').subscribe({ error: () => undefined });
    http.expectOne('api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(service.isAuthenticated()).toBe(false);
  });

  it('refresh : une seule requête pour des appels simultanés, puis nouveaux jetons', () => {
    service.login('a@b.co', 'motdepasse1').subscribe();
    http.expectOne('api/auth/login').flush(response());

    const tokens: string[] = [];
    service.refresh().subscribe((t) => tokens.push(t));
    service.refresh().subscribe((t) => tokens.push(t));
    const req = http.expectOne('api/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 's.r1' });
    req.flush(response('jwt2', 's.r2'));

    expect(tokens).toEqual(['jwt2', 'jwt2']);
    expect(service.token).toBe('jwt2');
    expect(localStorage.getItem('reveil.session')).toContain('s.r2');
  });

  it('refresh refusé : la session est fermée', () => {
    service.login('a@b.co', 'motdepasse1').subscribe();
    http.expectOne('api/auth/login').flush(response());

    service.refresh().subscribe({ error: () => undefined });
    http.expectOne('api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(service.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('reveil.session')).toBeNull();
  });

  it('logout révoque la session côté serveur puis la ferme localement', () => {
    service.register('a@b.co', 'motdepasse1').subscribe();
    http.expectOne('api/auth/register').flush(response());

    let done = false;
    service.logout().subscribe(() => (done = true));
    const req = http.expectOne('api/auth/logout');
    expect(req.request.body).toEqual({ refreshToken: 's.r1' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
    expect(service.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('reveil.session')).toBeNull();
  });

  it('logout ferme la session locale même si le serveur est injoignable', () => {
    service.login('a@b.co', 'motdepasse1').subscribe();
    http.expectOne('api/auth/login').flush(response());
    service.logout().subscribe();
    http.expectOne('api/auth/logout').error(new ProgressEvent('error'));
    expect(service.isAuthenticated()).toBe(false);
  });
});
