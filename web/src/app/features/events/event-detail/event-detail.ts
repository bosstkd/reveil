import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, concatMap, from, map } from 'rxjs';
import { ApiError } from '../../../core/auth/auth.models';
import {
  EVENT_GONE,
  EventReminder,
  REMINDER_STATUS,
  ReveilEvent,
  eventDate,
  eventTime,
  reminderAt,
  reminderDelay,
  timeUntil,
} from '../../../core/events/event.models';
import { EventService } from '../../../core/events/event.service';
import { HeicConverter } from '../../../core/photos/heic-converter';
import {
  EventPhoto,
  HEIC_CONVERSION_FAILED,
  PHOTO_ACCEPT,
  PHOTO_MAX_BYTES,
  PHOTO_MAX_MB,
  PHOTO_TOO_LARGE,
  PHOTO_UNSUPPORTED,
  PHOTO_UPLOAD_FAILED,
  batches,
  formatSize,
  photoKind,
  rejectReason,
} from '../../../core/photos/photo.models';
import { PhotoService } from '../../../core/photos/photo.service';
import { EventForm } from '../event-form/event-form';

/** Rafraîchissement du temps restant affiché. */
export const CLOCK_TICK_MS = 60_000;
export const LOAD_FAILED = "Impossible de charger l'événement.";
export const PHOTOS_LOAD_FAILED = 'Impossible de charger les photos.';

const plural = (n: number, word: string) => `${n} ${word}${n > 1 ? 's' : ''}`;

/** Le glisser concerne-t-il des fichiers (et non du texte ou un lien) ? */
function draggingFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}

/**
 * Détail d'un événement (`/evenements/:id`) : titre, date, heure, lieu, temps restant, rappels configurés
 * et galerie de miniatures ; accès à la modification et à l'import de photos (sélecteur multiple ou
 * glisser-déposer ; JPEG, PNG ou HEIC ≤ 15 Mo, HEIC convertis en JPEG avant l'envoi).
 */
@Component({
  selector: 'app-event-detail',
  imports: [EventForm, RouterLink],
  templateUrl: './event-detail.html',
  styleUrl: './event-detail.css',
  host: {
    // Un fichier lâché à côté de la zone de dépôt ne doit pas remplacer la page par son contenu.
    '(window:dragover)': 'ignoreStrayDrop($event)',
    '(window:drop)': 'ignoreStrayDrop($event)',
  },
})
export class EventDetail implements OnInit, OnDestroy {
  private readonly events = inject(EventService);
  private readonly photosApi = inject(PhotoService);
  private readonly heic = inject(HeicConverter);

  /** Paramètre de route `:id` (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly event = signal<ReveilEvent | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly photos = signal<EventPhoto[] | null>(null);
  protected readonly photosError = signal<string | null>(null);
  /** URL locales (blob:) des miniatures chargées, par identifiant de photo. */
  protected readonly thumbs = signal<Record<string, string>>({});
  protected readonly uploading = signal(false);
  /** Étape en cours de l'import (conversion, envoi). */
  protected readonly progress = signal<string | null>(null);
  /** Fichiers refusés (localement ou par l'API) et échecs d'envoi, un message par ligne. */
  protected readonly uploadErrors = signal<string[]>([]);
  /** Des fichiers sont glissés au-dessus de la zone de dépôt. */
  protected readonly dragging = signal(false);
  protected readonly editing = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly now = signal(new Date());

  protected readonly accept = PHOTO_ACCEPT;
  protected readonly date = eventDate;
  protected readonly time = eventTime;
  protected readonly delay = reminderDelay;
  protected readonly status = REMINDER_STATUS;

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  private readonly subscriptions = new Subscription();
  private timer?: ReturnType<typeof setInterval>;
  private destroyed = false;

  ngOnInit(): void {
    this.timer = setInterval(() => this.now.set(new Date()), CLOCK_TICK_MS);
    this.subscriptions.add(
      this.events.get(this.id()).subscribe({
        next: (event) => {
          this.now.set(new Date());
          this.event.set(event);
          this.loadPhotos(event.id);
        },
        error: (err: HttpErrorResponse) => this.error.set(err.status === 404 ? EVENT_GONE : LOAD_FAILED),
      }),
    );
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    clearInterval(this.timer);
    this.subscriptions.unsubscribe();
    Object.values(this.thumbs()).forEach((url) => URL.revokeObjectURL(url));
  }

  protected remaining(event: ReveilEvent): string {
    return timeUntil(event.startsAt, this.now());
  }

  protected at(event: ReveilEvent, reminder: EventReminder): string {
    return reminderAt(event, reminder);
  }

  protected startEditing(): void {
    this.notice.set(null);
    this.editing.set(true);
  }

  protected onUpdated(event: ReveilEvent): void {
    this.editing.set(false);
    this.now.set(new Date());
    this.event.set(event);
    this.notice.set(event.warnings.length ? event.warnings.join(' · ') : 'Événement modifié.');
  }

  protected onGone(): void {
    this.editing.set(false);
    this.event.set(null);
    this.error.set(EVENT_GONE);
  }

  protected choosePhoto(): void {
    this.uploadErrors.set([]);
    this.fileInput()?.nativeElement.click();
  }

  protected onFileSelected(input: HTMLInputElement): void {
    const files = Array.from(input.files ?? []);
    input.value = '';
    void this.importFiles(files);
  }

  protected ignoreStrayDrop(event: DragEvent): void {
    if (draggingFiles(event)) {
      event.preventDefault();
      if (event.type === 'drop') {
        this.dragging.set(false);
      }
    }
  }

  protected onDragOver(event: DragEvent): void {
    if (!draggingFiles(event)) {
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = this.uploading() ? 'none' : 'copy';
    }
    this.dragging.set(true);
  }

  protected onDragLeave(event: DragEvent): void {
    const zone = event.currentTarget as HTMLElement | null;
    // Passage au-dessus d'un élément enfant de la zone : on y est toujours.
    if (zone && event.relatedTarget instanceof Node && zone.contains(event.relatedTarget)) {
      return;
    }
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    void this.importFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  /**
   * Import de fichiers : refus local (format, taille) avec un message par fichier, conversion des HEIC en JPEG,
   * puis envoi par lots (`photos/batch`). Les photos enregistrées apparaissent en tête de la galerie.
   */
  async importFiles(files: File[]): Promise<void> {
    const event = this.event();
    if (!event || !files.length || this.uploading()) {
      return;
    }
    const errors: string[] = [];
    const accepted: File[] = [];
    for (const file of files) {
      const reason = rejectReason(file);
      if (reason) {
        errors.push(reason);
      } else {
        accepted.push(file);
      }
    }
    this.uploadErrors.set([...errors]);
    if (!accepted.length) {
      return;
    }
    this.uploading.set(true);
    const ready: File[] = [];
    for (const file of accepted) {
      if (photoKind(file) !== 'heic') {
        ready.push(file);
        continue;
      }
      this.progress.set(`Conversion de « ${file.name} »…`);
      try {
        const jpeg = await this.heic.toJpeg(file);
        if (jpeg.size > PHOTO_MAX_BYTES) {
          errors.push(
            `« ${file.name} » : photo trop volumineuse une fois convertie en JPEG (${formatSize(jpeg.size)} ; maximum ${PHOTO_MAX_MB} Mo).`,
          );
        } else {
          ready.push(jpeg);
        }
      } catch {
        errors.push(`« ${file.name} » : ${HEIC_CONVERSION_FAILED}`);
      }
      if (this.destroyed) {
        return;
      }
    }
    this.uploadErrors.set([...errors]);
    if (!ready.length) {
      this.finishImport();
      return;
    }
    this.send(event.id, ready, errors);
  }

  private send(eventId: string, files: File[], errors: string[]): void {
    let sent = 0;
    this.progress.set(`Envoi de ${plural(files.length, 'photo')}…`);
    this.subscriptions.add(
      from(batches(files))
        .pipe(concatMap((group) => this.photosApi.uploadAll(eventId, group).pipe(map((result) => ({ group, result })))))
        .subscribe({
          next: ({ group, result }) => {
            sent += group.length;
            // La galerie montre la plus récente d'abord ; l'API renvoie les photos dans l'ordre d'envoi.
            const saved = [...result.photos].reverse();
            this.photos.update((list) => [...saved, ...(list ?? []).filter((p) => !saved.some((s) => s.id === p.id))]);
            saved.forEach((p) => this.loadThumbnail(p));
            for (const r of result.rejected) {
              errors.push(`« ${r.filename ?? group[r.index]?.name ?? 'photo'} » : ${r.message}`);
            }
            this.uploadErrors.set([...errors]);
            if (sent < files.length) {
              this.progress.set(`Envoi des photos… (${sent}/${files.length})`);
            }
          },
          error: (err: HttpErrorResponse) => {
            this.finishImport();
            if (err.status === 404) {
              this.events.forget(eventId);
              this.onGone();
              return;
            }
            const body = err.error as ApiError | null;
            const reason =
              err.status === 413
                ? PHOTO_TOO_LARGE
                : err.status === 415
                  ? PHOTO_UNSUPPORTED
                  : err.status === 400 && body?.message
                    ? body.message
                    : PHOTO_UPLOAD_FAILED;
            const left = files.length - sent;
            errors.push(left === 1 && files.length === 1 ? reason : `${plural(left, 'photo')} non envoyée${left > 1 ? 's' : ''} : ${reason}`);
            this.uploadErrors.set([...errors]);
          },
          complete: () => this.finishImport(),
        }),
    );
  }

  private finishImport(): void {
    this.uploading.set(false);
    this.progress.set(null);
  }

  private loadPhotos(eventId: string): void {
    this.subscriptions.add(
      this.photosApi.list(eventId).subscribe({
        next: (photos) => {
          this.photos.set(photos);
          photos.forEach((p) => this.loadThumbnail(p));
        },
        error: () => this.photosError.set(PHOTOS_LOAD_FAILED),
      }),
    );
  }

  /** Miniature chargée avec le jeton (route protégée) puis affichée via une URL blob: locale. */
  private loadThumbnail(photo: EventPhoto): void {
    this.subscriptions.add(
      this.photosApi.thumbnail(photo).subscribe({
        next: (blob) => this.thumbs.update((t) => ({ ...t, [photo.id]: URL.createObjectURL(blob) })),
        error: () => {
          // Miniature indisponible : la vignette reste un emplacement vide.
        },
      }),
    );
  }
}
