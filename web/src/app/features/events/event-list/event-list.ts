import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/** Liste des événements de l'utilisateur. Contenu réel : stories suivantes. */
@Component({
  selector: 'app-event-list',
  template: `
    <main class="events">
      <header>
        <h1>Mes événements</h1>
        <button type="button" (click)="logout()" [disabled]="leaving()" data-testid="logout">Se déconnecter</button>
      </header>
      <p class="who">Connecté en tant que {{ auth.currentUser()?.email }}</p>
      <p data-testid="empty">Aucun événement pour le moment.</p>
    </main>
  `,
  styles: `
    .events { max-width: 600px; margin: 2rem auto; padding: 0 1rem; }
    header { display: flex; justify-content: space-between; align-items: center; }
    .who { color: #666; }
    button { padding: .5rem .9rem; border: 1px solid #3b5bdb; border-radius: 6px; background: #fff; color: #3b5bdb; cursor: pointer; }
  `,
})
export class EventList {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly leaving = signal(false);

  protected logout(): void {
    this.leaving.set(true);
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/connexion'));
  }
}
