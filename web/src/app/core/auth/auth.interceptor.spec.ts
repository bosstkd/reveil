import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  function loggedIn() {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt1', refreshToken: 's.r1', user: { id: 'u1', email: 'a@b.co' } }),
    );
  }

  function setUp() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => ctrl.verify());

  it('ajoute le jeton Bearer aux appels de l’API', () => {
    loggedIn();
    setUp();
    http.get('api/events').subscribe();
    expect(ctrl.expectOne('api/events').request.headers.get('Authorization')).toBe('Bearer jwt1');
  });

  it('n’ajoute pas de jeton sans session, ni aux endpoints d’ouverture de session, ni hors API', () => {
    setUp();
    http.get('api/events').subscribe({ error: () => undefined });
    expect(ctrl.expectOne('api/events').request.headers.has('Authorization')).toBe(false);

    TestBed.resetTestingModule();
    loggedIn();
    setUp();
    http.post('api/auth/login', {}).subscribe();
    http.post('api/auth/refresh', {}).subscribe();
    http.get('assets/x.json').subscribe();
    expect(ctrl.expectOne('api/auth/login').request.headers.has('Authorization')).toBe(false);
    expect(ctrl.expectOne('api/auth/refresh').request.headers.has('Authorization')).toBe(false);
    expect(ctrl.expectOne('assets/x.json').request.headers.has('Authorization')).toBe(false);
  });

  it('sur 401 : renouvelle le jeton puis rejoue la requête', () => {
    loggedIn();
    setUp();
    let body: unknown;
    http.get('api/events').subscribe((b) => (body = b));

    ctrl.expectOne('api/events').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl
      .expectOne('api/auth/refresh')
      .flush({ token: 'jwt2', expiresIn: 900, refreshToken: 's.r2', user: { id: 'u1', email: 'a@b.co' } });
    const retry = ctrl.expectOne('api/events');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer jwt2');
    retry.flush([{ id: 'e1' }]);

    expect(body).toEqual([{ id: 'e1' }]);
    expect(auth.token).toBe('jwt2');
  });

  it('si le renouvellement échoue : ferme la session et renvoie vers /connexion', () => {
    loggedIn();
    setUp();
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    let failed = false;
    http.get('api/events').subscribe({ error: () => (failed = true) });

    ctrl.expectOne('api/events').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/connexion');
  });

  it('laisse passer les autres erreurs sans renouvellement', () => {
    loggedIn();
    setUp();
    let status = 0;
    http.get('api/events').subscribe({ error: (e) => (status = e.status) });
    ctrl.expectOne('api/events').flush({}, { status: 500, statusText: 'Error' });
    ctrl.expectNone('api/auth/refresh');
    expect(status).toBe(500);
  });
});
