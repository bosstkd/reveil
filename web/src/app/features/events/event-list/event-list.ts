import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ReveilEvent } from '../../../core/events/event.models';
import { EventService } from '../../../core/events/event.service';
import { EventForm } from '../event-form/event-form';

/** Liste des événements de l'utilisateur, avec création en place. */
@Component({
  selector: 'app-event-list',
  imports: [EventForm],
  template: `
    <main class="events">
      <header>
        <h1>Mes événements</h1>
        <button type="button" (click)="logout()" [disabled]="leaving()" data-testid="logout">Se déconnecter</button>
      </header>
      <p class="who">Connecté en tant que {{ auth.currentUser()?.email }}</p>

      @if (creating()) {
        <app-event-form (created)="onCreated($event)" (cancelled)="creating.set(false)" />
      } @else {
        <button type="button" class="primary" (click)="creating.set(true)" data-testid="new-event">
          + Nouvel événement
        </button>
      }

      @if (notice(); as message) {
        <p class="notice" role="status" data-testid="created-notice">{{ message }}</p>
      }
      @if (loadError()) {
        <p class="error" role="alert" data-testid="load-error">Impossible de charger vos événements.</p>
      }

      @if (events.events().length === 0) {
        <p data-testid="empty">Aucun événement pour le moment.</p>
      } @else {
        <ul data-testid="event-list">
          @for (event of events.events(); track event.id) {
            <li [class.past]="event.past" data-testid="event-item">
              <strong>{{ event.title }}</strong>
              <span class="when">{{ when(event) }}</span>
              @if (event.location) {
                <span class="where">{{ event.location }}</span>
              }
              @if (event.past) {
                <span class="badge">Passé</span>
              }
            </li>
          }
        </ul>
      }
    </main>
  `,
  styles: `
    .events { max-width: 600px; margin: 2rem auto; padding: 0 1rem; }
    header { display: flex; justify-content: space-between; align-items: center; }
    .who { color: #666; }
    button { padding: .5rem .9rem; border: 1px solid #3b5bdb; border-radius: 6px; background: #fff; color: #3b5bdb; cursor: pointer; }
    button.primary { background: #3b5bdb; color: #fff; }
    ul { list-style: none; padding: 0; }
    li { display: flex; flex-direction: column; gap: .2rem; padding: .8rem 0; border-bottom: 1px solid #eee; }
    li.past { opacity: .65; }
    .when, .where { color: #555; font-size: .9rem; }
    .badge { align-self: flex-start; font-size: .75rem; padding: .1rem .4rem; border-radius: 4px; background: #eee; }
    .notice { padding: .5rem; border-radius: 6px; background: #fff3bf; color: #8a6100; }
    .error { color: #c92a2a; }
  `,
})
export class EventList implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly events = inject(EventService);
  private readonly router = inject(Router);
  protected readonly leaving = signal(false);
  protected readonly creating = signal(false);
  protected readonly loadError = signal(false);
  protected readonly notice = signal<string | null>(null);

  ngOnInit(): void {
    this.events.load().subscribe({ error: () => this.loadError.set(true) });
  }

  /** L'événement est déjà ajouté à la liste par EventService ; avertissement serveur éventuel (date passée). */
  protected onCreated(event: ReveilEvent): void {
    this.creating.set(false);
    this.notice.set(event.warnings.length ? event.warnings.join(' · ') : null);
  }

  /** Date/heure dans le fuseau de saisie de l'événement. */
  protected when(event: ReveilEvent): string {
    try {
      return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full', timeStyle: 'short', timeZone: event.timeZone })
        .format(new Date(event.startsAt));
    } catch {
      return event.localDateTime;
    }
  }

  protected logout(): void {
    this.leaving.set(true);
    this.auth.logout().subscribe(() => {
      this.events.clear();
      this.router.navigateByUrl('/connexion');
    });
  }
}
