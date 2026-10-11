import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login').then((m) => m.Login) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'rubros' },
      { path: 'rubros', loadComponent: () => import('./features/rubros/rubros').then((m) => m.Rubros) },
    ],
  },
  { path: '**', redirectTo: '' },
];
