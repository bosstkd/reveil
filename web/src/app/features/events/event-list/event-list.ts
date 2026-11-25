import { Component, inject } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';

/** Liste des événements de l'utilisateur. Contenu réel : stories suivantes. */
@Component({
  selector: 'app-event-list',
  template: `
    <main class="events">
      <h1>Mes événements</h1>
      <p class="who">Connecté en tant que {{ auth.currentUser()?.email }}</p>
      <p data-testid="empty">Aucun événement pour le moment.</p>
    </main>
  `,
  styles: `.events { max-width: 600px; margin: 2rem auto; padding: 0 1rem; } .who { color: #666; }`,
})
export class EventList {
  protected readonly auth = inject(AuthService);
}
