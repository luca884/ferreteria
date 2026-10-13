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
      {
        path: 'articulos',
        loadComponent: () => import('./features/articulos/articulos').then((m) => m.Articulos),
      },
      // 'nuevo' va antes que ':id' para que no se tome como un id.
      {
        path: 'articulos/nuevo',
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'articulos/:id',
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'proveedores',
        loadComponent: () => import('./features/proveedores/proveedores').then((m) => m.Proveedores),
      },
      {
        path: 'importaciones',
        loadComponent: () =>
          import('./features/importaciones/importaciones').then((m) => m.Importaciones),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
