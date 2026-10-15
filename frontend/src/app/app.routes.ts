import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { inicioPorRol } from './core/auth/inicio-por-rol';

/** Título de la pestaña: la pantalla primero, para distinguir pestañas abiertas en paralelo. */
const titulo = (pantalla: string) => `${pantalla} · Ferretería`;

export const routes: Routes = [
  {
    path: 'login',
    title: titulo('Ingresar'),
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: inicioPorRol },
      // Sin guard de rol: lo usan el Vendedor y el Dueño.
      {
        path: 'mostrador',
        title: titulo('Mostrador'),
        loadComponent: () => import('./features/mostrador/mostrador').then((m) => m.Mostrador),
      },
      {
        path: 'rubros',
        title: titulo('Rubros'),
        loadComponent: () => import('./features/rubros/rubros').then((m) => m.Rubros),
      },
      {
        path: 'articulos',
        title: titulo('Artículos'),
        loadComponent: () => import('./features/articulos/articulos').then((m) => m.Articulos),
      },
      // 'nuevo' va antes que ':id' para que no se tome como un id.
      {
        path: 'articulos/nuevo',
        title: titulo('Nuevo artículo'),
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'articulos/:id',
        title: titulo('Artículo'),
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'proveedores',
        title: titulo('Proveedores'),
        loadComponent: () => import('./features/proveedores/proveedores').then((m) => m.Proveedores),
      },
      {
        path: 'importaciones',
        title: titulo('Importar lista'),
        loadComponent: () =>
          import('./features/importaciones/importaciones').then((m) => m.Importaciones),
      },
      // Sin guard de rol, como importaciones: el Vendedor ve el aviso de la pantalla y el backend responde 403.
      {
        path: 'actualizacion-masiva',
        title: titulo('Actualizar precios'),
        loadComponent: () =>
          import('./features/actualizacion-masiva/actualizacion-masiva').then(
            (m) => m.ActualizacionMasiva,
          ),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
