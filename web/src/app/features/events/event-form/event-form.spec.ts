import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EventForm } from './event-form';
import { userTimeZone } from '../../../core/events/event.models';

describe('EventForm', () => {
  let fixture: ComponentFixture<EventForm>;
  let http: HttpTestingController;
  let el: HTMLElement;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const type = (id: string, value: string) => {
    const input = q<HTMLInputElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [EventForm],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EventForm);
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('bloque la création si le titre, la date ou l’heure manquent', () => {
    q('event-submit')!.click();
    fixture.detectChanges();
    expect(q('title-error')!.textContent).toContain('Le titre est obligatoire');
    expect(q('date-error')!.textContent).toContain('La date est obligatoire');
    expect(q('time-error')!.textContent).toContain("L'heure est obligatoire");

    type('event-title', '   ');
    type('event-date', '2099-01-01');
    type('event-time', '10:00');
    q('event-submit')!.click();
    fixture.detectChanges();
    expect(q('title-error')).not.toBeNull();
    http.expectNone('api/events');
  });

  it('envoie la date/heure locale avec le fuseau horaire de l’utilisateur', () => {
    const created = vi.fn();
    fixture.componentInstance.created.subscribe(created);
    expect(q('event-timezone')!.textContent).toContain(userTimeZone());

    type('event-title', '  Concert ');
    type('event-date', '2099-12-24');
    type('event-time', '20:30');
    type('event-location', ' ');
    q('event-submit')!.click();

    const req = http.expectOne({ method: 'POST', url: 'api/events' });
    expect(req.request.body).toEqual({
      title: 'Concert',
      date: '2099-12-24',
      time: '20:30',
      timeZone: userTimeZone(),
      location: null,
    });
    req.flush({ id: 'e1', title: 'Concert', warnings: [] });
    expect(created).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }));
  });

  it('avertit pour une date passée sans bloquer la création', () => {
    type('event-title', 'Hier');
    type('event-date', '2001-01-01');
    type('event-time', '08:00');
    expect(q('past-warning')!.textContent).toContain('déjà passée');

    q('event-submit')!.click();
    http.expectOne({ method: 'POST', url: 'api/events' }).flush({ id: 'e1', warnings: [] });
  });

  it('pas d’avertissement pour une date future', () => {
    type('event-date', '2099-01-01');
    type('event-time', '08:00');
    expect(q('past-warning')).toBeNull();
  });

  it('affiche les erreurs de validation du serveur', () => {
    type('event-title', 'Concert');
    type('event-date', '2099-12-24');
    type('event-time', '20:30');
    q('event-submit')!.click();
    http
      .expectOne('api/events')
      .flush(
        { message: 'Données invalides', errors: [{ field: 'timeZone', message: 'Le fuseau horaire est inconnu' }] },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();
    expect(q('event-error')!.textContent).toContain('Le fuseau horaire est inconnu');
    expect(q<HTMLButtonElement>('event-submit')!.disabled).toBe(false);
  });
});
