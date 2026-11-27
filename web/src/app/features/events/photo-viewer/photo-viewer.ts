import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { EventPhoto, PHOTO_CONTENT_FAILED, PHOTO_DELETE_FAILED } from '../../../core/photos/photo.models';
import { PhotoService } from '../../../core/photos/photo.service';

/** Déplacement horizontal minimal (px) d'un glissé pour passer à la photo voisine. */
export const SWIPE_MIN_PX = 50;

/**
 * Visionneuse plein écran des photos d'un événement : photo d'origine (route protégée, chargée avec le jeton
 * puis affichée via une URL blob: ; la miniature sert d'aperçu pendant le chargement), défilement vers les
 * suivantes/précédentes (boutons, flèches du clavier, glissé tactile), photos voisines préchargées, et
 * suppression après confirmation en place. La photo supprimée est signalée au parent (`deleted`) qui la retire
 * de la galerie ; la visionneuse passe alors à la suivante (ou se ferme s'il n'en reste aucune).
 */
@Component({
  selector: 'app-photo-viewer',
  templateUrl: './photo-viewer.html',
  styleUrl: './photo-viewer.css',
  host: {
    '(document:keydown)': 'onKey($event)',
  },
})
export class PhotoViewer implements AfterViewInit, OnDestroy {
  private readonly api = inject(PhotoService);

  /** Galerie (la plus récente d'abord), tenue à jour par le parent. */
  readonly photos = input.required<EventPhoto[]>();
  /** URL blob: des miniatures déjà chargées par la galerie. */
  readonly thumbs = input<Record<string, string>>({});
  /** Position de la photo ouverte. */
  readonly start = input(0);

  readonly closed = output<void>();
  readonly deleted = output<EventPhoto>();

  private readonly index = signal<number | null>(null);
  /** Position affichée, bornée à la galerie (qui raccourcit après une suppression). */
  protected readonly position = computed(() => {
    const count = this.photos().length;
    return Math.min(Math.max(this.index() ?? this.start(), 0), Math.max(count - 1, 0));
  });
  protected readonly current = computed<EventPhoto | undefined>(() => this.photos()[this.position()]);
  /** URL blob: des photos d'origine chargées, par identifiant. */
  protected readonly full = signal<Record<string, string>>({});
  protected readonly failed = signal<Record<string, boolean>>({});
  protected readonly confirming = signal(false);
  protected readonly deleting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly contentFailed = PHOTO_CONTENT_FAILED;

  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private readonly loading = new Set<string>();
  private readonly subscriptions = new Subscription();
  private swipeStartX: number | null = null;

  constructor() {
    // Photo affichée et ses voisines : chargées à la demande (jamais toute la galerie en pleine taille).
    effect(() => {
      const photos = this.photos();
      if (!photos.length) {
        this.closed.emit();
        return;
      }
      const at = this.position();
      [at, at + 1, at - 1].filter((i) => i >= 0 && i < photos.length).forEach((i) => this.load(photos[i]));
    });
  }

  ngAfterViewInit(): void {
    this.closeButton()?.nativeElement.focus();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    Object.values(this.full()).forEach((url) => URL.revokeObjectURL(url));
  }

  protected hasPrevious(): boolean {
    return this.position() > 0;
  }

  protected hasNext(): boolean {
    return this.position() < this.photos().length - 1;
  }

  protected previous(): void {
    if (this.hasPrevious() && !this.deleting()) this.go(this.position() - 1);
  }

  protected next(): void {
    if (this.hasNext() && !this.deleting()) this.go(this.position() + 1);
  }

  protected close(): void {
    this.closed.emit();
  }

  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (this.confirming() && !this.deleting()) this.confirming.set(false);
      else this.close();
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.next();
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.previous();
    }
  }

  protected onPointerDown(event: PointerEvent): void {
    this.swipeStartX = event.clientX;
  }

  protected onPointerUp(event: PointerEvent): void {
    if (this.swipeStartX === null) return;
    const dx = event.clientX - this.swipeStartX;
    this.swipeStartX = null;
    if (dx <= -SWIPE_MIN_PX) this.next();
    else if (dx >= SWIPE_MIN_PX) this.previous();
  }

  protected askDelete(): void {
    this.error.set(null);
    this.confirming.set(true);
  }

  protected cancelDelete(): void {
    this.confirming.set(false);
  }

  protected confirmDelete(): void {
    const photo = this.current();
    if (!photo || this.deleting()) return;
    this.deleting.set(true);
    this.error.set(null);
    this.subscriptions.add(
      this.api.delete(photo).subscribe({
        next: () => this.removed(photo),
        error: (err: HttpErrorResponse) => {
          // Déjà supprimée (autre appareil) : le résultat attendu est atteint.
          if (err.status === 404) {
            this.removed(photo);
            return;
          }
          this.deleting.set(false);
          this.error.set(PHOTO_DELETE_FAILED);
        },
      }),
    );
  }

  private removed(photo: EventPhoto): void {
    this.deleting.set(false);
    this.confirming.set(false);
    const url = this.full()[photo.id];
    if (url) {
      URL.revokeObjectURL(url);
      this.full.update(({ [photo.id]: _, ...rest }) => rest);
    }
    // Même position : la suivante prend la place de la photo supprimée.
    this.index.set(this.position());
    this.deleted.emit(photo);
  }

  private go(index: number): void {
    this.index.set(index);
    this.confirming.set(false);
    this.error.set(null);
  }

  private load(photo: EventPhoto): void {
    if (this.loading.has(photo.id)) return;
    this.loading.add(photo.id);
    this.subscriptions.add(
      this.api.content(photo).subscribe({
        next: (blob) => this.full.update((f) => ({ ...f, [photo.id]: URL.createObjectURL(blob) })),
        error: () => this.failed.update((f) => ({ ...f, [photo.id]: true })),
      }),
    );
  }
}
