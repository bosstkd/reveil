import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'evenements' },
  {
    path: 'inscription',
    title: 'Créer un compte — Reveil',
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
  },
  {
    path: 'evenements',
    title: 'Mes événements — Reveil',
    canActivate: [authGuard],
    loadComponent: () => import('./features/events/event-list/event-list').then((m) => m.EventList),
  },
  { path: '**', redirectTo: 'evenements' },
];
