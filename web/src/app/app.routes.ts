import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'evenements' },
  {
    path: 'connexion',
    title: 'Se connecter — Reveil',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
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
  {
    path: 'evenements/:id',
    title: 'Détail de l’événement — Reveil',
    canActivate: [authGuard],
    loadComponent: () => import('./features/events/event-detail/event-detail').then((m) => m.EventDetail),
  },
  {
    path: 'parametres',
    title: 'Paramètres — Reveil',
    canActivate: [authGuard],
    loadComponent: () => import('./features/settings/settings').then((m) => m.Settings),
  },
  { path: '**', redirectTo: 'evenements' },
];
