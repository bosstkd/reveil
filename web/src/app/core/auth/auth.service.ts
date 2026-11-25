import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthResponse, AuthUser } from './auth.models';

const STORAGE_KEY = 'reveil.session';

interface Session {
  token: string;
  user: AuthUser;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = signal<Session | null>(readSession());

  readonly currentUser = computed(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  get token(): string | null {
    return this.session()?.token ?? null;
  }

  /** Crée le compte puis ouvre la session. Chemin relatif : fonctionne sous n'importe quel base href. */
  register(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('api/auth/register', { email, password })
      .pipe(tap((res) => this.open({ token: res.token, user: res.user })));
  }

  logout(): void {
    localStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
  }

  private open(session: Session): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    this.session.set(session);
  }
}

function readSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}
