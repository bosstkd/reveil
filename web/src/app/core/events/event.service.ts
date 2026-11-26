import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { CreateEventPayload, EventPage, EventPeriod, PAGE_SIZE, ReveilEvent, UpdateEventPayload } from './event.models';

/** Une liste paginée (un onglet). */
interface Tab {
  items: ReveilEvent[];
  /** Dernière page chargée (-1 : rien chargé). */
  page: number;
  hasNext: boolean;
  total: number;
}

const EMPTY: Tab = { items: [], page: -1, hasNext: false, total: 0 };

/** À venir : du plus proche au plus lointain ; passés : du plus récent au plus ancien. */
const ORDER: Record<EventPeriod, (a: ReveilEvent, b: ReveilEvent) => number> = {
  upcoming: (a, b) => a.startsAt.localeCompare(b.startsAt),
  past: (a, b) => b.startsAt.localeCompare(a.startsAt),
};

function merge(period: EventPeriod, base: ReveilEvent[], extra: ReveilEvent[]): ReveilEvent[] {
  const ids = new Set(extra.map((e) => e.id));
  return [...base.filter((e) => !ids.has(e.id)), ...extra].sort(ORDER[period]);
}

/** Événements de l'utilisateur connecté. Chemins relatifs : fonctionne sous n'importe quel base href. */
@Injectable({ providedIn: 'root' })
export class EventService {
  private readonly http = inject(HttpClient);
  private readonly tabs = signal<Record<EventPeriod, Tab>>({ upcoming: EMPTY, past: EMPTY });

  readonly upcoming = computed(() => this.tabs().upcoming);
  readonly past = computed(() => this.tabs().past);

  tab(period: EventPeriod): Tab {
    return this.tabs()[period];
  }

  /** Charge la première page (`more` : la page suivante) de l'onglet. */
  load(period: EventPeriod, more = false): Observable<EventPage> {
    const page = more ? this.tab(period).page + 1 : 0;
    const params = new HttpParams().set('when', period).set('page', page).set('size', PAGE_SIZE);
    return this.http.get<EventPage>('api/events', { params }).pipe(
      tap((res) =>
        this.patchTab(period, (tab) => ({
          items: merge(period, more ? tab.items : [], res.items),
          page: res.page,
          hasNext: res.hasNext,
          total: res.totalElements,
        })),
      ),
    );
  }

  /** Détail d'un événement de l'utilisateur connecté (404 s'il n'existe pas ou n'est pas à lui). */
  get(id: string): Observable<ReveilEvent> {
    return this.http.get<ReveilEvent>(`api/events/${encodeURIComponent(id)}`);
  }

  /** Crée l'événement et l'ajoute immédiatement à l'onglet correspondant (passé ou à venir). */
  create(payload: CreateEventPayload): Observable<ReveilEvent> {
    return this.http.post<ReveilEvent>('api/events', payload).pipe(
      tap((created) => {
        const period: EventPeriod = created.past ? 'past' : 'upcoming';
        this.patchTab(period, (tab) => {
          const isNew = !tab.items.some((e) => e.id === created.id);
          return { ...tab, items: merge(period, tab.items, [created]), total: tab.total + (isNew ? 1 : 0) };
        });
      }),
    );
  }

  /**
   * Modifie l'événement (PUT) et le replace aussitôt dans le bon onglet : un changement de date peut le faire
   * passer de « À venir » à « Passés » (ou l'inverse).
   */
  update(id: string, payload: UpdateEventPayload): Observable<ReveilEvent> {
    return this.http.put<ReveilEvent>(`api/events/${encodeURIComponent(id)}`, payload).pipe(
      tap((updated) => {
        this.forget(id);
        const period: EventPeriod = updated.past ? 'past' : 'upcoming';
        this.patchTab(period, (tab) => ({ ...tab, items: merge(period, tab.items, [updated]), total: tab.total + 1 }));
      }),
    );
  }

  /** Supprime l'événement (DELETE) ; ses rappels sont supprimés côté serveur. Retiré aussitôt de la liste. */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`api/events/${encodeURIComponent(id)}`).pipe(tap(() => this.forget(id)));
  }

  /** Retire l'événement des onglets (supprimé, ou disparu côté serveur). */
  forget(id: string): void {
    for (const period of ['upcoming', 'past'] as const) {
      this.patchTab(period, (tab) =>
        tab.items.some((e) => e.id === id)
          ? { ...tab, items: tab.items.filter((e) => e.id !== id), total: Math.max(0, tab.total - 1) }
          : tab,
      );
    }
  }

  clear(): void {
    this.tabs.set({ upcoming: EMPTY, past: EMPTY });
  }

  private patchTab(period: EventPeriod, fn: (tab: Tab) => Tab): void {
    this.tabs.update((tabs) => ({ ...tabs, [period]: fn(tabs[period]) }));
  }
}
