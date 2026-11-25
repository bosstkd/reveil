import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { EventList } from './event-list';
import { AuthService } from '../../../core/auth/auth.service';
import { ReveilEvent } from '../../../core/events/event.models';

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

describe('EventList', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let fixture: ReturnType<typeof TestBed.createComponent<EventList>>;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const type = (id: string, value: string) => {
    const input = q<HTMLInputElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

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
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('charge et affiche les événements de l’utilisateur', () => {
    http.expectOne('api/events').flush([event()]);
    fixture.detectChanges();
    expect(q('empty')).toBeNull();
    expect(el.querySelectorAll('[data-testid="event-item"]').length).toBe(1);
    expect(q('event-list')!.textContent).toContain('Concert');
    expect(q('event-list')!.textContent).toContain('20:30'); // heure dans le fuseau de saisie
    expect(q('event-list')!.textContent).toContain('Olympia');
  });

  it('l’événement créé apparaît immédiatement dans la liste, sans rechargement', () => {
    http.expectOne('api/events').flush([]);
    fixture.detectChanges();
    expect(q('empty')).not.toBeNull();

    q('new-event')!.click();
    fixture.detectChanges();
    type('event-title', 'Concert');
    type('event-date', '2099-12-24');
    type('event-time', '20:30');
    q('event-submit')!.click();

    http.expectOne({ method: 'POST', url: 'api/events' }).flush(event(), { status: 201, statusText: 'Created' });
    fixture.detectChanges();

    expect(q('event-form')).toBeNull();
    expect(q('event-list')!.textContent).toContain('Concert');
    expect(q('created-notice')).toBeNull();
    http.expectNone('api/events');
  });

  it('affiche l’avertissement renvoyé pour une date passée', () => {
    http.expectOne('api/events').flush([]);
    q('new-event')!.click();
    fixture.detectChanges();
    type('event-title', 'Hier');
    type('event-date', '2001-01-01');
    type('event-time', '08:00');
    q('event-submit')!.click();
    http
      .expectOne({ method: 'POST', url: 'api/events' })
      .flush(event({ title: 'Hier', past: true, warnings: ['La date de cet événement est déjà passée'] }));
    fixture.detectChanges();

    expect(q('created-notice')!.textContent).toContain('déjà passée');
    expect(q('event-list')!.textContent).toContain('Passé');
  });

  it('« Se déconnecter » révoque la session de cet appareil et renvoie vers /connexion', () => {
    http.expectOne('api/events').flush([]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.detectChanges();
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
