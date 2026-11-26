import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import {
  EVENT_FORBIDDEN,
  EVENT_GONE,
  EventPeriod,
  ReveilEvent,
  eventDate,
  eventTime,
  timeUntil,
} from '../../../core/events/event.models';
import { EventService } from '../../../core/events/event.service';
import { EventForm } from '../event-form/event-form';

/** Rafraîchissement du temps restant affiché. */
const CLOCK_TICK_MS = 60_000;

/**
 * Liste des événements de l'utilisateur (onglets « À venir » / « Passés »), avec création, modification
 * et suppression (après confirmation) en place.
 */
@Component({
  selector: 'app-event-list',
  imports: [EventForm],
  templateUrl: './event-list.html',
  styleUrl: './event-list.css',
})
export class EventList implements OnInit, OnDestroy {
  protected readonly auth = inject(AuthService);
  protected readonly events = inject(EventService);
  private readonly router = inject(Router);
  protected readonly leaving = signal(false);
  protected readonly creating = signal(false);
  protected readonly loading = signal(false);
  protected readonly loadError = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly period = signal<EventPeriod>('upcoming');
  protected readonly now = signal(new Date());
  /** Événement en cours de modification. */
  protected readonly editingId = signal<string | null>(null);
  /** Événement dont la suppression attend confirmation. */
  protected readonly confirmingId = signal<string | null>(null);
  protected readonly deleting = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly current = computed(() => (this.period() === 'upcoming' ? this.events.upcoming() : this.events.past()));
  /** Liste chargée et vide : état vide de l'onglet. */
  protected readonly empty = computed(() => this.current().page >= 0 && this.current().items.length === 0);

  protected readonly date = eventDate;
  protected readonly time = eventTime;

  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.fetch('upcoming');
    this.timer = setInterval(() => this.now.set(new Date()), CLOCK_TICK_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected select(period: EventPeriod): void {
    this.period.set(period);
    if (this.events.tab(period).page < 0) {
      this.fetch(period);
    }
  }

  protected loadMore(): void {
    this.fetch(this.period(), true);
  }

  protected remaining(event: ReveilEvent): string {
    return timeUntil(event.startsAt, this.now());
  }

  /** L'événement est déjà ajouté à la liste par EventService ; avertissement serveur éventuel (date passée). */
  protected onCreated(event: ReveilEvent): void {
    this.creating.set(false);
    this.now.set(new Date());
    this.notice.set(event.warnings.length ? event.warnings.join(' · ') : null);
  }

  protected startCreating(): void {
    this.notice.set(null);
    this.actionError.set(null);
    this.editingId.set(null);
    this.confirmingId.set(null);
    this.creating.set(true);
  }

  protected startEditing(event: ReveilEvent): void {
    this.notice.set(null);
    this.actionError.set(null);
    this.creating.set(false);
    this.confirmingId.set(null);
    this.editingId.set(event.id);
  }

  /** L'événement est déjà replacé dans le bon onglet par EventService. */
  protected onUpdated(event: ReveilEvent): void {
    this.editingId.set(null);
    this.now.set(new Date());
    this.notice.set(event.warnings.length ? event.warnings.join(' · ') : 'Événement modifié.');
  }

  protected onGone(): void {
    this.editingId.set(null);
    this.actionError.set(EVENT_GONE);
  }

  protected askDelete(event: ReveilEvent): void {
    this.notice.set(null);
    this.actionError.set(null);
    this.editingId.set(null);
    this.confirmingId.set(event.id);
  }

  protected confirmDelete(event: ReveilEvent): void {
    if (this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.events.remove(event.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.confirmingId.set(null);
        this.notice.set(`« ${event.title} » a été supprimé, ainsi que ses rappels.`);
      },
      error: (err: HttpErrorResponse) => {
        this.deleting.set(false);
        this.confirmingId.set(null);
        if (err.status === 404) {
          this.events.forget(event.id);
          this.actionError.set(EVENT_GONE);
        } else {
          this.actionError.set(
            err.status === 403 ? EVENT_FORBIDDEN : "La suppression a échoué. Réessayez dans un instant.",
          );
        }
      },
    });
  }

  protected logout(): void {
    this.leaving.set(true);
    this.auth.logout().subscribe(() => {
      this.events.clear();
      this.router.navigateByUrl('/connexion');
    });
  }

  private fetch(period: EventPeriod, more = false): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.events.load(period, more).subscribe({
      next: () => {
        this.loading.set(false);
        this.now.set(new Date());
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }
}
