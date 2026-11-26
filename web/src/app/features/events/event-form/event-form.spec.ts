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

  describe('en modification', () => {
    const concert = {
      id: 'e1',
      title: 'Concert',
      startsAt: '2099-12-24T19:30:00Z',
      localDateTime: '2099-12-24T20:30:00',
      timeZone: 'America/New_York',
      location: 'Olympia',
      past: false,
      warnings: [],
    };

    beforeEach(() => {
      fixture = TestBed.createComponent(EventForm);
      fixture.componentRef.setInput('event', concert);
      el = fixture.nativeElement;
      fixture.detectChanges();
    });

    it('pré-remplit toutes les informations saisies, dans le fuseau de l’événement', () => {
      expect(el.querySelector('h2')!.textContent).toContain("Modifier l'événement");
      expect(q<HTMLInputElement>('event-title')!.value).toBe('Concert');
      expect(q<HTMLInputElement>('event-date')!.value).toBe('2099-12-24');
      expect(q<HTMLInputElement>('event-time')!.value).toBe('20:30');
      expect(q<HTMLInputElement>('event-location')!.value).toBe('Olympia');
      expect(q('event-timezone')!.textContent).toContain('America/New_York');
      expect(q('event-submit')!.textContent).toContain('Enregistrer');
    });

    it('envoie un PUT avec toutes les informations modifiées', () => {
      const updated = vi.fn();
      fixture.componentInstance.updated.subscribe(updated);
      type('event-title', 'Concert reporté');
      type('event-date', '2099-12-31');
      type('event-time', '22:00');
      type('event-location', '');
      q('event-submit')!.click();

      const req = http.expectOne({ method: 'PUT', url: 'api/events/e1' });
      expect(req.request.body).toEqual({
        title: 'Concert reporté',
        date: '2099-12-31',
        time: '22:00',
        timeZone: 'America/New_York',
        location: null,
      });
      req.flush({ ...concert, title: 'Concert reporté' });
      expect(updated).toHaveBeenCalledWith(expect.objectContaining({ title: 'Concert reporté' }));
    });

    it('bloque l’enregistrement si un champ obligatoire est vidé', () => {
      type('event-title', '');
      q('event-submit')!.click();
      fixture.detectChanges();
      expect(q('title-error')).not.toBeNull();
      http.expectNone('api/events/e1');
    });

    it('signale un refus (403) et un événement disparu (404)', () => {
      const gone = vi.fn();
      fixture.componentInstance.gone.subscribe(gone);
      q('event-submit')!.click();
      http.expectOne('api/events/e1').flush({ message: 'Accès refusé', errors: [] }, { status: 403, statusText: 'Forbidden' });
      fixture.detectChanges();
      expect(q('event-error')!.textContent).toContain("Vous n'êtes pas autorisé");

      q('event-submit')!.click();
      http.expectOne('api/events/e1').flush({ message: 'Événement introuvable', errors: [] }, { status: 404, statusText: 'Not Found' });
      expect(gone).toHaveBeenCalledWith('e1');
    });

    it('avertit (sans bloquer) si la nouvelle date est passée', () => {
      type('event-date', '2001-01-01');
      expect(q('past-warning')!.textContent).toContain('modification sera tout de même enregistrée');
    });
  });
});
