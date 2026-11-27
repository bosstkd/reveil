import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CLOCK_TICK_MS, EventDetail, LOAD_FAILED } from './event-detail';
import { EVENT_GONE, ReveilEvent, reminderDelay } from '../../../core/events/event.models';
import { HeicConverter } from '../../../core/photos/heic-converter';
import {
  EventPhoto,
  HEIC_CONVERSION_FAILED,
  PHOTO_ACCEPT,
  PHOTO_DELETE_FAILED,
  PHOTO_TOO_LARGE,
  PHOTO_UPLOAD_FAILED,
  relativeApiUrl,
} from '../../../core/photos/photo.models';

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

  describe('import de photos', () => {
    const MB = 1024 * 1024;
    const jpeg = (name = 'photo.jpg', size = 3) => new File([new Uint8Array(size)], name, { type: 'image/jpeg' });
    const errors = () => all('upload-error-item').map((e) => e.textContent!.trim());
    /** Laisse s'exécuter les promesses en attente (conversion HEIC). */
    const settle = async () => {
      for (let i = 0; i < 5; i++) await Promise.resolve();
      await new Promise((r) => setTimeout(r));
      render();
    };

    const select = (...files: File[]) => {
      const input = q<HTMLInputElement>('photo-input')!;
      Object.defineProperty(input, 'files', { value: files, configurable: true });
      input.dispatchEvent(new Event('change'));
      render();
    };

    const dragEvent = (type: string, files: File[] = [], types = ['Files']) => {
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'dataTransfer', { value: { files, types, dropEffect: 'none' } });
      return event;
    };

    const expectBatch = (names: string[]) => {
      const req = http.expectOne({ method: 'POST', url: 'api/events/e1/photos/batch' });
      const sent = (req.request.body as FormData).getAll('files') as File[];
      expect(sent.map((f) => f.name)).toEqual(names);
      return req;
    };

    it('le bouton ouvre le sélecteur multiple JPEG / PNG / HEIC', () => {
      open();
      const input = q<HTMLInputElement>('photo-input')!;
      expect(input.getAttribute('accept')).toBe(PHOTO_ACCEPT);
      expect(input.getAttribute('accept')).toContain('.heic');
      expect(input.multiple).toBe(true);
      const click = vi.spyOn(input, 'click').mockImplementation(() => {});
      q('add-photo')!.click();
      expect(click).toHaveBeenCalled();
    });

    it('envoie les photos choisies en un seul envoi et les ajoute en tête de la galerie', () => {
      open(concert(), [photo('p1')]);
      select(jpeg('a.jpg'), new File(['png'], 'b.png', { type: 'image/png' }));
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(true);
      expect(q('upload-progress')!.textContent).toContain('Envoi de 2 photos');

      expectBatch(['a.jpg', 'b.png']).flush({ photos: [photo('p2'), photo('p3')], rejected: [] });
      http.expectOne('api/events/e1/photos/p3/thumbnail').flush(new Blob(['3']));
      http.expectOne('api/events/e1/photos/p2/thumbnail').flush(new Blob(['2']));
      render();

      // La plus récente (dernière envoyée) d'abord.
      expect((all('gallery-photo') as HTMLImageElement[]).map((i) => i.getAttribute('src'))).toEqual([
        'blob:thumb-2',
        'blob:thumb-3',
        'blob:thumb-1',
      ]);
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(false);
      expect(q('upload-progress')).toBeNull();
      expect(q('upload-error')).toBeNull();
    });

    it('glisser-déposer : la zone se met en évidence puis envoie les fichiers déposés', () => {
      open();
      const zone = q('drop-zone')!;
      const over = dragEvent('dragover');
      zone.dispatchEvent(over);
      render();
      expect(over.defaultPrevented).toBe(true);
      expect(zone.classList).toContain('dragging');

      zone.dispatchEvent(dragEvent('dragleave'));
      render();
      expect(zone.classList).not.toContain('dragging');

      zone.dispatchEvent(dragEvent('dragenter'));
      const drop = dragEvent('drop', [jpeg('d.jpg')]);
      zone.dispatchEvent(drop);
      render();
      expect(drop.defaultPrevented).toBe(true);
      expect(zone.classList).not.toContain('dragging');
      expectBatch(['d.jpg']).flush({ photos: [photo('p9')], rejected: [] });
      http.expectOne('api/events/e1/photos/p9/thumbnail').flush(new Blob(['9']));
      render();
      expect(all('gallery-photo')).toHaveLength(1);
    });

    it('ignore un glisser qui ne porte pas de fichiers et empêche un dépôt hors zone d’ouvrir le fichier', () => {
      open();
      const text = dragEvent('dragover', [], ['text/plain']);
      q('drop-zone')!.dispatchEvent(text);
      render();
      expect(text.defaultPrevented).toBe(false);
      expect(q('drop-zone')!.classList).not.toContain('dragging');

      const stray = dragEvent('drop', [jpeg()]);
      window.dispatchEvent(stray);
      expect(stray.defaultPrevented).toBe(true);
      http.expectNone('api/events/e1/photos/batch');
    });

    it('refuse localement, avec un message explicite, un autre format ou un fichier de plus de 15 Mo, et envoie le reste', () => {
      open();
      select(
        new File(['gif'], 'anim.gif', { type: 'image/gif' }),
        jpeg('ok.jpg'),
        new File([new Uint8Array(15 * MB + 1)], 'enorme.jpg', { type: 'image/jpeg' }),
        new File(['%PDF'], 'notes.pdf', { type: '' }),
      );
      expect(errors()).toEqual([
        '« anim.gif » : format non pris en charge (JPEG, PNG ou HEIC uniquement).',
        '« enorme.jpg » : fichier trop volumineux (15 Mo ; maximum 15 Mo).',
        '« notes.pdf » : format non pris en charge (JPEG, PNG ou HEIC uniquement).',
      ]);
      expectBatch(['ok.jpg']).flush({ photos: [photo('p2')], rejected: [] });
      http.expectOne('api/events/e1/photos/p2/thumbnail').flush(new Blob(['2']));
      render();
      expect(errors()).toHaveLength(3);
    });

    it('accepte un fichier de 15 Mo exactement', () => {
      open();
      select(new File([new Uint8Array(15 * MB)], 'limite.png', { type: 'image/png' }));
      expect(q('upload-error')).toBeNull();
      expectBatch(['limite.png']).flush({ photos: [], rejected: [] });
    });

    it('rien d’acceptable : aucun envoi', () => {
      open();
      select(new File(['x'], 'film.mov', { type: 'video/quicktime' }));
      http.expectNone('api/events/e1/photos/batch');
      expect(errors()).toEqual(['« film.mov » : format non pris en charge (JPEG, PNG ou HEIC uniquement).']);
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(false);
    });

    it('convertit les photos HEIC en JPEG avant l’envoi', async () => {
      let done!: (f: File) => void;
      const toJpeg = vi
        .spyOn(TestBed.inject(HeicConverter), 'toJpeg')
        .mockImplementation(() => new Promise<File>((resolve) => (done = resolve)));
      open();
      // Hors Safari, un HEIC arrive souvent sans type MIME : reconnu à son extension.
      select(new File(['heic'], 'IMG_0001.HEIC', { type: '' }), jpeg('a.jpg'));
      expect(q('upload-progress')!.textContent).toContain('Conversion de « IMG_0001.HEIC »');
      http.expectNone('api/events/e1/photos/batch');

      done(new File(['jpeg'], 'IMG_0001.jpg', { type: 'image/jpeg' }));
      await settle();
      expect(toJpeg).toHaveBeenCalledTimes(1);
      const req = expectBatch(['IMG_0001.jpg', 'a.jpg']);
      expect(((req.request.body as FormData).getAll('files') as File[])[0].type).toBe('image/jpeg');
      req.flush({ photos: [], rejected: [] });
    });

    it('HEIC trop volumineux une fois converti : refusé avec un message', async () => {
      vi.spyOn(TestBed.inject(HeicConverter), 'toJpeg').mockResolvedValue(
        new File([new Uint8Array(16 * MB)], 'IMG.jpg', { type: 'image/jpeg' }),
      );
      open();
      select(new File(['heic'], 'IMG.heic', { type: 'image/heic' }));
      await settle();
      http.expectNone('api/events/e1/photos/batch');
      expect(errors()[0]).toContain('« IMG.heic » : photo trop volumineuse une fois convertie en JPEG (16 Mo');
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(false);
    });

    it('HEIC illisible : message pour ce fichier, les autres partent', async () => {
      vi.spyOn(TestBed.inject(HeicConverter), 'toJpeg').mockRejectedValue(new Error('libheif'));
      open();
      select(new File(['?'], 'casse.heic', { type: 'image/heic' }), jpeg('a.jpg'));
      await settle();
      expect(errors()).toEqual([`« casse.heic » : ${HEIC_CONVERSION_FAILED}`]);
      expectBatch(['a.jpg']).flush({ photos: [], rejected: [] });
    });

    it('affiche les fichiers refusés par l’API, avec leur nom', () => {
      open();
      select(jpeg('a.jpg'), jpeg('faux.jpg'));
      expectBatch(['a.jpg', 'faux.jpg']).flush({
        photos: [photo('p2')],
        rejected: [{ index: 1, filename: 'faux.jpg', status: 415, message: 'Format non pris en charge' }],
      });
      http.expectOne('api/events/e1/photos/p2/thumbnail').flush(new Blob(['2']));
      render();
      expect(errors()).toEqual(['« faux.jpg » : Format non pris en charge']);
      expect(all('gallery-photo')).toHaveLength(1);
    });

    it('plus de 10 fichiers : envoyés en plusieurs lots successifs', () => {
      open();
      const files = Array.from({ length: 12 }, (_, i) => jpeg(`p${i}.jpg`));
      select(...files);
      expectBatch(files.slice(0, 10).map((f) => f.name)).flush({ photos: [], rejected: [] });
      render();
      expect(q('upload-progress')!.textContent).toContain('10/12');
      expectBatch(['p10.jpg', 'p11.jpg']).flush({ photos: [], rejected: [] });
      render();
      expect(q('upload-progress')).toBeNull();
    });

    it('échec d’envoi : message et bouton réactivé', () => {
      open();
      select(jpeg('a.jpg'), jpeg('b.jpg'));
      expectBatch(['a.jpg', 'b.jpg']).flush(null, { status: 503, statusText: 'Unavailable' });
      render();
      expect(errors()).toEqual([`2 photos non envoyées : ${PHOTO_UPLOAD_FAILED}`]);
      expect(q<HTMLButtonElement>('add-photo')!.disabled).toBe(false);
    });

    it('envoi refusé en bloc (413) : message de taille', () => {
      open();
      select(jpeg('a.jpg'));
      expectBatch(['a.jpg']).flush({ message: 'Envoi trop volumineux', errors: [] }, { status: 413, statusText: 'Too Large' });
      render();
      expect(errors()).toEqual([PHOTO_TOO_LARGE]);
    });

    it('événement supprimé entre-temps (404) : message dédié', () => {
      open();
      select(jpeg('a.jpg'));
      expectBatch(['a.jpg']).flush({ message: 'Événement introuvable', errors: [] }, { status: 404, statusText: 'Not Found' });
      render();
      expect(q('detail-error')!.textContent).toContain(EVENT_GONE);
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

  describe('visionneuse plein écran', () => {
    const content = (id: string) => `api/events/e1/photos/${id}/content`;
    /** Répond aux téléchargements de photos d'origine en attente ; renvoie les identifiants demandés. */
    const flushContents = () => {
      const reqs = http.match((r) => r.url.endsWith('/content'));
      reqs.forEach((r) => {
        expect(r.request.responseType).toBe('blob');
        r.flush(new Blob(['full'], { type: 'image/jpeg' }));
      });
      render();
      return reqs.map((r) => r.request.url);
    };
    const key = (k: string) => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: k }));
      render();
    };
    const counter = () => q('viewer-counter')!.textContent!.trim();
    const openAt = (i: number) => {
      all('gallery-open')[i].click();
      render();
    };

    it('un clic sur une miniature ouvre la photo en plein écran (originale, voisines préchargées)', () => {
      open(concert(), [photo('p3'), photo('p2'), photo('p1')]);
      expect(q('photo-viewer')).toBeNull();
      openAt(1);
      expect(q('photo-viewer')!.getAttribute('role')).toBe('dialog');
      expect(counter()).toBe('2 / 3');
      // Aperçu (miniature) pendant le chargement de l'original.
      expect(q('viewer-loading')).not.toBeNull();
      expect(q<HTMLImageElement>('viewer-preview')!.getAttribute('src')).toBe('blob:thumb-2');
      expect(flushContents().sort()).toEqual([content('p1'), content('p2'), content('p3')]);
      expect(q('viewer-image')).not.toBeNull();
      expect(q('viewer-loading')).toBeNull();
      expect(document.activeElement).toBe(q('viewer-close'));
    });

    it('défilement vers les suivantes : boutons, flèches du clavier, glissé ; bornes désactivées', () => {
      open(concert(), [photo('p4'), photo('p3'), photo('p2'), photo('p1')]);
      openAt(0);
      expect(flushContents().sort()).toEqual([content('p3'), content('p4')]);
      expect(q<HTMLButtonElement>('viewer-previous')!.disabled).toBe(true);

      q('viewer-next')!.click();
      render();
      expect(counter()).toBe('2 / 4');
      expect(flushContents()).toEqual([content('p2')]); // seule la nouvelle voisine est chargée

      key('ArrowRight');
      expect(counter()).toBe('3 / 4');
      expect(flushContents()).toEqual([content('p1')]);

      const viewer = q('photo-viewer')!;
      viewer.dispatchEvent(Object.assign(new Event('pointerdown'), { clientX: 300 }));
      viewer.dispatchEvent(Object.assign(new Event('pointerup'), { clientX: 120 }));
      render();
      expect(counter()).toBe('4 / 4');
      expect(q<HTMLButtonElement>('viewer-next')!.disabled).toBe(true);
      key('ArrowRight');
      expect(counter()).toBe('4 / 4');

      viewer.dispatchEvent(Object.assign(new Event('pointerdown'), { clientX: 100 }));
      viewer.dispatchEvent(Object.assign(new Event('pointerup'), { clientX: 400 }));
      render();
      key('ArrowLeft');
      expect(counter()).toBe('2 / 4');
      expect(flushContents()).toEqual([]);
    });

    it('Échap ou ✕ ferme la visionneuse et libère les URL des originaux', () => {
      open(concert(), [photo('p1')]);
      openAt(0);
      flushContents();
      key('Escape');
      expect(q('photo-viewer')).toBeNull();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:thumb-2');

      openAt(0);
      flushContents();
      q('viewer-close')!.click();
      render();
      expect(q('photo-viewer')).toBeNull();
    });

    it('original indisponible : message dans la visionneuse', () => {
      open(concert(), [photo('p1')]);
      openAt(0);
      http.expectOne(content('p1')).flush(null, { status: 500, statusText: 'Error' });
      render();
      expect(q('viewer-image-error')).not.toBeNull();
    });

    it('la suppression demande une confirmation puis retire la photo de la galerie', () => {
      open(concert(), [photo('p3'), photo('p2'), photo('p1')]);
      openAt(1);
      flushContents();

      q('viewer-delete')!.click();
      render();
      expect(q('viewer-delete-confirm')!.textContent).toContain('Supprimer cette photo ?');
      q('viewer-delete-cancel')!.click();
      render();
      expect(q('viewer-delete-confirm')).toBeNull();
      http.expectNone({ method: 'DELETE' });

      q('viewer-delete')!.click();
      render();
      q('viewer-delete-confirm-button')!.click();
      render();
      expect(q<HTMLButtonElement>('viewer-delete-confirm-button')!.disabled).toBe(true);
      http.expectOne({ method: 'DELETE', url: 'api/events/e1/photos/p2' }).flush(null, { status: 204, statusText: 'No Content' });
      render();

      // La suivante prend la place de la photo supprimée ; galerie mise à jour sans rechargement.
      expect(counter()).toBe('2 / 2');
      expect(all('gallery-open')).toHaveLength(2);
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:thumb-2'); // miniature de p2
      expect(q('detail-notice')!.textContent).toContain('Photo supprimée.');
      http.expectNone('api/events/e1/photos');
      flushContents();
    });

    it('suppression de la dernière photo : la visionneuse se ferme, galerie vide', () => {
      open(concert(), [photo('p1')]);
      openAt(0);
      flushContents();
      q('viewer-delete')!.click();
      render();
      q('viewer-delete-confirm-button')!.click();
      // Déjà supprimée sur un autre appareil (404) : même résultat.
      http.expectOne({ method: 'DELETE', url: 'api/events/e1/photos/p1' })
        .flush({ message: 'Photo introuvable', errors: [] }, { status: 404, statusText: 'Not Found' });
      render();
      expect(q('photo-viewer')).toBeNull();
      expect(q('photos-empty')).not.toBeNull();
    });

    it('échec de la suppression : message, la photo reste', () => {
      open(concert(), [photo('p1')]);
      openAt(0);
      flushContents();
      q('viewer-delete')!.click();
      render();
      q('viewer-delete-confirm-button')!.click();
      http.expectOne({ method: 'DELETE', url: 'api/events/e1/photos/p1' }).flush(null, { status: 500, statusText: 'Error' });
      render();
      expect(q('viewer-error')!.textContent).toContain(PHOTO_DELETE_FAILED);
      expect(q('photo-viewer')).not.toBeNull();
      expect(all('gallery-open')).toHaveLength(1);
    });

    it('galerie de 50 photos : seules les miniatures sont téléchargées tant que rien n’est ouvert', () => {
      const many = Array.from({ length: 50 }, (_, i) => photo(`p${50 - i}`));
      open(concert(), many);
      expect(all('gallery-photo')).toHaveLength(50);
      http.expectNone((r) => r.url.endsWith('/content'));
    });
  });
});
