import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap, throwError } from 'rxjs';
import { AuthResponse, AuthUser } from './auth.models';

const STORAGE_KEY = 'reveil.session';

/** Endpoints d'ouverture de session : jamais de jeton Bearer ni de tentative de rafraîchissement. */
export const PUBLIC_AUTH_URLS = [
  'api/auth/register',
  'api/auth/login',
  'api/auth/refresh',
  'api/auth/password-reset/request',
  'api/auth/password-reset/confirm',
];

interface Session {
  token: string;
  refreshToken: string;
  user: AuthUser;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = signal<Session | null>(readSession());
  private refreshing: Observable<string> | null = null;

  readonly currentUser = computed(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  get token(): string | null {
    return this.session()?.token ?? null;
  }

  /** Crée le compte puis ouvre la session. Chemin relatif : fonctionne sous n'importe quel base href. */
  register(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('api/auth/register', { email, password }).pipe(tap((res) => this.open(res)));
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('api/auth/login', { email, password }).pipe(tap((res) => this.open(res)));
  }

  /**
   * Renouvelle le jeton d'accès. Les appels simultanés partagent la même requête
   * (le jeton de rafraîchissement change à chaque usage). Échec : la session est fermée.
   */
  refresh(): Observable<string> {
    const refreshToken = this.session()?.refreshToken;
    if (!refreshToken) {
      return throwError(() => new Error('Aucune session'));
    }
    if (!this.refreshing) {
      this.refreshing = this.http.post<AuthResponse>('api/auth/refresh', { refreshToken }).pipe(
        tap((res) => this.open(res)),
        map((res) => res.token),
        catchError((err) => {
          this.clear();
          return throwError(() => err);
        }),
        finalize(() => (this.refreshing = null)),
        shareReplay(1),
      );
    }
    return this.refreshing;
  }

  /** Déconnexion de cet appareil : révoque la session côté serveur puis l'efface localement. */
  logout(): Observable<void> {
    const refreshToken = this.session()?.refreshToken;
    if (!refreshToken) {
      this.clear();
      return of(undefined);
    }
    return this.http.post<void>('api/auth/logout', { refreshToken }).pipe(
      catchError(() => of(undefined)), // hors ligne : la session locale est tout de même fermée
      map(() => undefined),
      tap(() => this.clear()),
    );
  }

  clear(): void {
    localStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
  }

  private open(res: AuthResponse): void {
    const session: Session = { token: res.token, refreshToken: res.refreshToken, user: res.user };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    this.session.set(session);
  }
}

function readSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const s = raw ? (JSON.parse(raw) as Partial<Session>) : null;
    return s?.token && s.refreshToken && s.user ? (s as Session) : null;
  } catch {
    return null;
  }
}
