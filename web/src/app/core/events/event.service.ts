import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { CreateEventPayload, ReveilEvent } from './event.models';

const byStart = (a: ReveilEvent, b: ReveilEvent) => a.startsAt.localeCompare(b.startsAt);

/** Événements de l'utilisateur connecté. Chemins relatifs : fonctionne sous n'importe quel base href. */
@Injectable({ providedIn: 'root' })
export class EventService {
  private readonly http = inject(HttpClient);
  private readonly list = signal<ReveilEvent[]>([]);

  /** Liste triée par date ; mise à jour dès qu'un événement est créé. */
  readonly events = this.list.asReadonly();

  load(): Observable<ReveilEvent[]> {
    return this.http.get<ReveilEvent[]>('api/events').pipe(tap((events) => this.list.set([...events].sort(byStart))));
  }

  create(payload: CreateEventPayload): Observable<ReveilEvent> {
    return this.http
      .post<ReveilEvent>('api/events', payload)
      .pipe(tap((created) => this.list.update((events) => [...events.filter((e) => e.id !== created.id), created].sort(byStart))));
  }

  clear(): void {
    this.list.set([]);
  }
}
