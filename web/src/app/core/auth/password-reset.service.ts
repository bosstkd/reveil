import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/** Mot de passe oublié (routes publiques, chemins relatifs). */
@Injectable({ providedIn: 'root' })
export class PasswordResetService {
  private readonly http = inject(HttpClient);

  /** Demande l'envoi d'un lien par e-mail (202, réponse identique que le compte existe ou non). */
  requestReset(email: string): Observable<void> {
    return this.http.post('api/auth/password-reset/request', { email }).pipe(map(() => undefined));
  }

  /** Choisit le nouveau mot de passe à l'aide du jeton reçu par e-mail (204). Toutes les sessions sont fermées. */
  confirmReset(token: string, password: string): Observable<void> {
    return this.http.post<void>('api/auth/password-reset/confirm', { token, password });
  }
}
