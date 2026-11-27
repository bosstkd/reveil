import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/** Message de l'API (403) quand le mot de passe de confirmation est refusé. */
export const INCORRECT_PASSWORD_MESSAGE = 'Mot de passe incorrect';

@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /**
   * Supprime définitivement le compte et toutes ses données (confirmation par mot de passe).
   * Succès : la session locale est effacée (côté serveur, toutes les sessions sont déjà révoquées).
   */
  deleteAccount(password: string): Observable<void> {
    return this.http.delete<void>('api/account', { body: { password } }).pipe(tap(() => this.auth.clear()));
  }
}
