import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
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
import {
  EventPhoto,
  PHOTO_ACCEPT,
  PHOTO_MAX_BYTES,
  PHOTO_TOO_LARGE,
  PHOTO_UNSUPPORTED,
  PHOTO_UPLOAD_FAILED,
} from '../../../core/photos/photo.models';
import { PhotoService } from '../../../core/photos/photo.service';
import { EventForm } from '../event-form/event-form';

/** Rafraîchissement du temps restant affiché. */
export const CLOCK_TICK_MS = 60_000;
export const LOAD_FAILED = "Impossible de charger l'événement.";
export const PHOTOS_LOAD_FAILED = 'Impossible de charger les photos.';

/**
 * Détail d'un événement (`/evenements/:id`) : titre, date, heure, lieu, temps restant, rappels configurés
 * et galerie de miniatures ; accès à l'ajout de photo et à la modification.
 */
@Component({
  selector: 'app-event-detail',
  imports: [EventForm, RouterLink],
  templateUrl: './event-detail.html',
  styleUrl: './event-detail.css',
})
export class EventDetail implements OnInit, OnDestroy {
  private readonly events = inject(EventService);
  private readonly photosApi = inject(PhotoService);

  /** Paramètre de route `:id` (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly event = signal<ReveilEvent | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly photos = signal<EventPhoto[] | null>(null);
  protected readonly photosError = signal<string | null>(null);
  /** URL locales (blob:) des miniatures chargées, par identifiant de photo. */
  protected readonly thumbs = signal<Record<string, string>>({});
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);
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
    this.uploadError.set(null);
    this.fileInput()?.nativeElement.click();
  }

  protected onFileSelected(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    const event = this.event();
    if (!file || !event) {
      return;
    }
    if (file.size > PHOTO_MAX_BYTES) {
      this.uploadError.set(PHOTO_TOO_LARGE);
      return;
    }
    this.uploading.set(true);
    this.uploadError.set(null);
    this.subscriptions.add(
      this.photosApi.upload(event.id, file).subscribe({
        next: (photo) => {
          this.uploading.set(false);
          this.photos.update((list) => [photo, ...(list ?? []).filter((p) => p.id !== photo.id)]);
          this.loadThumbnail(photo);
        },
        error: (err: HttpErrorResponse) => {
          this.uploading.set(false);
          if (err.status === 404) {
            this.events.forget(event.id);
            this.onGone();
            return;
          }
          const body = err.error as ApiError | null;
          this.uploadError.set(
            err.status === 413
              ? PHOTO_TOO_LARGE
              : err.status === 415
                ? PHOTO_UNSUPPORTED
                : err.status === 400 && body?.message
                  ? body.message
                  : PHOTO_UPLOAD_FAILED,
          );
        },
      }),
    );
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
