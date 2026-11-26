import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CLOCK_TICK_MS, EventDetail, LOAD_FAILED } from './event-detail';
import { EVENT_GONE, ReveilEvent, reminderDelay } from '../../../core/events/event.models';
import { EventPhoto, PHOTO_TOO_LARGE, PHOTO_UNSUPPORTED, relativeApiUrl } from '../../../core/photos/photo.models';

const concert = (over: Partial<ReveilEvent> = {}): ReveilEvent => ({
  id: 'e1',
  title: 'Concert',
  startsAt: '2099-12-24T19:30:00Z',
  localDateTime: '2099-12-24T20:30:00',
  timeZone: 'Europe/Paris',
  location: 'Olympia',
  past: false,
  warnings: [],
  reminders: [{ minutesBefore: 60, remindAt: '2099-12-24T18:30:00Z', status: 'scheduled' }],
  ...over,
});

const photo = (id: string): EventPhoto => ({
  id,
  eventId: 'e1',
  contentType: 'image/jpeg',
  size: 812_000,
  width: 2048,
  height: 1536,
  createdAt: '2099-12-24T20:00:00Z',
  url: `/api/events/e1/photos/${id}/content`,
  thumbnailUrl: `/api/events/e1/photos/${id}/thumbnail`,
});

describe('reminderDelay', () => {
  it('formate le délai avant l’événement', () => {
    expect(reminderDelay(60)).toBe('1 h avant');
    expect(reminderDelay(30)).toBe('30 min avant');
    expect(reminderDelay(90)).toBe('1 h 30 min avant');
    expect(reminderDelay(1440)).toBe('1 jour avant');
    expect(reminderDelay(2880)).toBe('2 jours avant');
  });

  it('rend relatives les URL de l’API (base href quelconque)', () => {
    expect(relativeApiUrl('/api/events/e1/photos/p1/thumbnail')).toBe('api/events/e1/photos/p1/thumbnail');
  });
});

describe('EventDetail', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let fixture: ReturnType<typeof TestBed.createComponent<EventDetail>>;
  let blobCount = 0;

  const q = <T extends HTMLElement = HTMLElement>(id: string) => el.querySelector<T>(`[data-testid="${id}"]`);
  const all = (id: string) => Array.from(el.querySelectorAll<HTMLElement>(`[data-testid="${id}"]`));
  const render = () => fixture.detectChanges();

  /** Ouvre le détail de e1 : GET événement puis GET photos (et miniatures). */
  function open(event: ReveilEvent | null = concert(), photos: EventPhoto[] = []) {
    const req = http.expectOne({ method: 'GET', url: 'api/events/e1' });
    if (event) {
      req.flush(event);
      http.expectOne({ method: 'GET', url: 'api/events/e1/photos' }).flush(photos);
      for (const p of photos) {
        const thumb = http.expectOne(`api/events/e1/photos/${p.id}/thumbnail`);
        expect(thumb.request.responseType).toBe('blob');
        thumb.flush(new Blob(['x'], { type: 'image/jpeg' }));
      }
    } else {
      req.flush({ message: 'Événement introuvable', errors: [] }, { status: 404, statusText: 'Not Found' });
    }
    render();
  }

  beforeEach(() => {
    blobCount = 0;
    URL.createObjectURL = vi.fn(() => `blob:thumb-${++blobCount}`);
    URL.revokeObjectURL = vi.fn();
    TestBed.configureTestingModule({
      imports: [EventDetail],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EventDetail);
    fixture.componentRef.setInput('id', 'e1');
    el = fixture.nativeElement;
    render();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('affiche titre, date, heure, lieu, temps restant et rappels configurés', () => {
    expect(q('detail-loading')).not.toBeNull();
    open();
    expect(q('detail-title')!.textContent).toBe('Concert');
    expect(q('detail-date')!.textContent).toContain('24 décembre 2099');
    expect(q('detail-time')!.textContent).toContain('20:30'); // fuseau de saisie
    expect(q('detail-location')!.textContent).toContain('Olympia');
    expect(q('detail-remaining')!.textContent).toMatch(/^dans \d+ ans$/);
    const reminders = all('detail-reminder');
    expect(reminders).toHaveLength(1);
    expect(reminders[0].textContent).toMatch(/1 h avant · .*19:30.* · programmé/);
  });

  it('sans rappel : l’indique', () => {
    open(concert({ reminders: [] }));
    expect(q('reminders-empty')!.textContent).toContain('Aucun rappel configuré.');
    expect(all('detail-reminder')).toHaveLength(0);
  });

  it('affiche les photos en galerie de miniatures chargées avec le jeton', () => {
    open(concert(), [photo('p2'), photo('p1')]);
    const imgs = all('gallery-photo') as HTMLImageElement[];
    expect(imgs).toHaveLength(2);
    expect(imgs.map((i) => i.getAttribute('src'))).toEqual(['blob:thumb-1', 'blob:thumb-2']);

    fixture.destroy();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:thumb-1');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:thumb-2');
  });

  it('galerie vide : message', () => {
    open();
    expect(q('photos-empty')!.textContent).toContain('Aucune photo');
  });

  it('événement inexistant ou d’un autre utilisateur (404) : message dédié', () => {
    open(null);
    expect(q('detail-error')!.textContent).toContain(EVENT_GONE);
    expect(q('event-detail')).toBeNull();
  });

  it('erreur serveur : message de chargement', () => {
    http.expectOne('api/events/e1').flush(null, { status: 500, statusText: 'Error' });
    render();
    expect(q('detail-error')!.textContent).toContain(LOAD_FAILED);
  });

  it('« Modifier » ouvre le formulaire pré-rempli et met à jour le détail', () => {
    open();
    q('detail-edit')!.click();
    render();
    const title = el.querySelector<HTMLInputElement>('input[formcontrolname="title"]')!;
    expect(title.value).toBe('Concert');
    title.value = 'Concert reporté';
    title.dispatchEvent(new Event('input'));
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'PUT', url: 'api/events/e1' }).flush(concert({ title: 'Concert reporté' }));
    render();
    expect(q('detail-title')!.textContent).toBe('Concert reporté');
    expect(q('detail-notice')!.textContent).toContain('Événement modifié.');
  });

  describe('ajout de photo', () => {
    const select = (file: File) => {
      const input = q<HTMLInputElement>('photo-input')!;
      Object.defineProperty(input, 'files', { value: [file], configurable: true });
      input.dispatchEvent(new Event('change'));
      render();
    };

    it('le bouton ouvre le sélecteur (appareil photo sur mobile)', () => {
      open();
      const input = q<HTMLInputElement>('photo-input')!;
      expect(input.getAttribute('accept')).toBe('image/jpeg,image/png');
      expect(input.getAttribute('capture')).toBe('environment');
      const click = vi.spyOn(input, 'click').mockImplementation(() => {});
      q('add-photo')!.click();
      expect(click).toHaveBeenCalled();
    });

    it('envoie la photo et l’ajoute en tête de la galerie', () => {
      open(concert(), [photo('p1')]);
      select(new File(['img'], 'photo.jpg', { type: 'image/jpeg' }));
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(true);

      const req = http.expectOne({ method: 'POST', url: 'api/events/e1/photos' });
      expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
      req.flush(photo('p2'));
      http.expectOne('api/events/e1/photos/p2/thumbnail').flush(new Blob(['y']));
      render();

      expect((all('gallery-photo') as HTMLImageElement[]).map((i) => i.getAttribute('src'))).toEqual([
        'blob:thumb-2',
        'blob:thumb-1',
      ]);
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(false);
    });

    it('refuse localement une photo de plus de 10 Mo', () => {
      open();
      select(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' }));
      expect(q('upload-error')!.textContent).toContain(PHOTO_TOO_LARGE);
    });

    it('affiche le refus du serveur (format non pris en charge)', () => {
      open();
      select(new File(['gif'], 'a.gif', { type: 'image/gif' }));
      http
        .expectOne({ method: 'POST', url: 'api/events/e1/photos' })
        .flush({ message: 'Type non pris en charge', errors: [] }, { status: 415, statusText: 'Unsupported' });
      render();
      expect(q('upload-error')!.textContent).toContain(PHOTO_UNSUPPORTED);
    });
  });

  it('rafraîchit le temps restant chaque minute', () => {
    fixture.destroy();
    vi.useFakeTimers({ now: new Date('2099-12-24T19:00:30Z') });
    http.match('api/events/e1');
    fixture = TestBed.createComponent(EventDetail);
    fixture.componentRef.setInput('id', 'e1');
    el = fixture.nativeElement;
    render();
    open();
    expect(q('detail-remaining')!.textContent).toBe('dans 29 minutes');
    vi.advanceTimersByTime(CLOCK_TICK_MS);
    render();
    expect(q('detail-remaining')!.textContent).toBe('dans 28 minutes');
  });
});
