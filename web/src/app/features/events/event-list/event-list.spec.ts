import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { EventList } from './event-list';
import { AuthService } from '../../../core/auth/auth.service';
import { EventPage, EventPeriod, ReveilEvent } from '../../../core/events/event.models';

const event = (over: Partial<ReveilEvent> = {}): ReveilEvent => ({
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [],
  ...over,
});

const page = (when: EventPeriod, items: ReveilEvent[], over: Partial<EventPage> = {}): EventPage => ({
  when,
  items,
  page: 0,
  size: 100,
  totalElements: items.length,
  hasNext: false,
  ...over,
});

describe('EventList', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let fixture: ReturnType<typeof TestBed.createComponent<EventList>>;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const all = (id: string) => Array.from(el.querySelectorAll(`[data-testid="${id}"]`)).map((n) => n.textContent!.trim());
  const type = (id: string, value: string) => {
    const input = q<HTMLInputElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  /** Requête GET /api/events en attente pour l'onglet `when`. */
  const list = (when: EventPeriod, p = 0): TestRequest =>
    http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === 'api/events' &&
        r.params.get('when') === when &&
        r.params.get('page') === String(p) &&
        r.params.get('size') === '100',
    );
  const render = () => fixture.detectChanges();

  beforeEach(() => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'a@b.co' } }),
    );
    TestBed.configureTestingModule({
      imports: [EventList],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EventList);
    render();
    el = fixture.nativeElement;
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('charge les événements à venir et affiche titre, date, heure et temps restant', () => {
    list('upcoming').flush(page('upcoming', [event()]));
    render();
    expect(q('empty')).toBeNull();
    expect(all('item-title')).toEqual(['Concert']);
    expect(q('item-date')!.textContent).toContain('24 décembre 2099');
    expect(q('item-time')!.textContent).toContain('20:30'); // heure dans le fuseau de saisie
    expect(q('item-remaining')!.textContent).toMatch(/^dans \d+ ans$/);
    expect(q('event-list')!.textContent).toContain('Olympia');
    expect(q('tab-upcoming')!.textContent).toContain('(1)');
  });

  it('conserve l’ordre chronologique croissant des événements à venir', () => {
    list('upcoming').flush(
      page('upcoming', [
        event({ id: 'b', title: 'Plus tard', startsAt: '2099-12-25T10:00:00Z' }),
        event({ id: 'a', title: 'Bientôt', startsAt: '2099-01-01T10:00:00Z' }),
      ]),
    );
    render();
    expect(all('item-title')).toEqual(['Bientôt', 'Plus tard']);
  });

  it('affiche les événements passés dans un onglet séparé, chargé à la demande', () => {
    list('upcoming').flush(page('upcoming', [event()]));
    render();
    http.expectNone((r) => r.params.get('when') === 'past');

    q('tab-past')!.click();
    render();
    list('past').flush(page('past', [event({ id: 'p1', title: 'Hier', startsAt: '2001-01-01T08:00:00Z', past: true })]));
    render();

    expect(all('item-title')).toEqual(['Hier']);
    expect(q('item-remaining')!.textContent).toMatch(/^il y a \d+ ans$/);
    expect(q('tab-past')!.getAttribute('aria-selected')).toBe('true');

    // Retour sur « À venir » : déjà chargé, pas de nouvelle requête.
    q('tab-upcoming')!.click();
    render();
    expect(all('item-title')).toEqual(['Concert']);
  });

  it('« Afficher plus » charge la page suivante', () => {
    list('upcoming').flush(page('upcoming', [event({ id: 'a', title: 'A' })], { totalElements: 2, hasNext: true }));
    render();
    q('load-more')!.click();
    list('upcoming', 1).flush(
      page('upcoming', [event({ id: 'b', title: 'B', startsAt: '2100-01-01T00:00:00Z' })], { page: 1 }),
    );
    render();
    expect(all('item-title')).toEqual(['A', 'B']);
    expect(q('load-more')).toBeNull();
  });

  it('affiche 100 événements', () => {
    const items = Array.from({ length: 100 }, (_, i) =>
      event({ id: `e${i}`, title: `Événement ${i}`, startsAt: new Date(Date.UTC(2099, 0, 1, i)).toISOString() }),
    );
    list('upcoming').flush(page('upcoming', items));
    const start = performance.now();
    render();
    expect(el.querySelectorAll('[data-testid="event-item"]').length).toBe(100);
    expect(performance.now() - start).toBeLessThan(2000);
  });

  it('état vide : bouton « Créer mon premier événement » qui ouvre le formulaire', () => {
    list('upcoming').flush(page('upcoming', []));
    render();
    expect(q('empty')!.textContent).toContain('Aucun événement à venir');
    expect(q('new-event')).toBeNull();
    expect(q('create-first')!.textContent).toContain('Créer mon premier événement');

    q('create-first')!.click();
    render();
    expect(q('event-form')).not.toBeNull();
    expect(q('create-first')).toBeNull();
  });

  it('signale une erreur de chargement', () => {
    list('upcoming').flush(null, { status: 500, statusText: 'Error' });
    render();
    expect(q('load-error')).not.toBeNull();
  });

  it('l’événement créé apparaît immédiatement dans la liste, sans rechargement', () => {
    list('upcoming').flush(page('upcoming', []));
    render();

    q('create-first')!.click();
    render();
    type('event-title', 'Concert');
    type('event-date', '2099-12-24');
    type('event-time', '20:30');
    q('event-submit')!.click();

    http.expectOne({ method: 'POST', url: 'api/events' }).flush(event(), { status: 201, statusText: 'Created' });
    render();

    expect(q('event-form')).toBeNull();
    expect(all('item-title')).toEqual(['Concert']);
    expect(q('created-notice')).toBeNull();
    expect(q('tab-upcoming')!.textContent).toContain('(1)');
    http.expectNone('api/events');
  });

  it('un événement créé dans le passé avertit et rejoint l’onglet « Passés »', () => {
    list('upcoming').flush(page('upcoming', []));
    render();
    q('create-first')!.click();
    render();
    type('event-title', 'Hier');
    type('event-date', '2001-01-01');
    type('event-time', '08:00');
    q('event-submit')!.click();
    http
      .expectOne({ method: 'POST', url: 'api/events' })
      .flush(event({ title: 'Hier', past: true, warnings: ['La date de cet événement est déjà passée'] }));
    render();

    expect(q('created-notice')!.textContent).toContain('déjà passée');
    expect(q('event-list')).toBeNull();

    q('tab-past')!.click();
    render();
    list('past').flush(page('past', [event({ title: 'Hier', past: true })]));
    render();
    expect(all('item-title')).toEqual(['Hier']);
  });

  it('« Se déconnecter » révoque la session de cet appareil et renvoie vers /connexion', () => {
    list('upcoming').flush(page('upcoming', []));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    render();
    expect(el.textContent).toContain('a@b.co');

    q<HTMLButtonElement>('logout')!.click();
    const req = http.expectOne('api/auth/logout');
    expect(req.request.body).toEqual({ refreshToken: 's.r' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(localStorage.getItem('reveil.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/connexion');
  });
});
