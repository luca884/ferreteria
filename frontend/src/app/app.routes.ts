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
      // Sin guard de rol: los presupuestos los usan el Vendedor y el Dueño.
      {
        path: 'presupuestos',
        title: titulo('Presupuestos'),
        loadComponent: () => import('./features/presupuestos/presupuestos').then((m) => m.Presupuestos),
      },
      // Sin guard de rol: los remitos los usan el Vendedor y el Dueño; anular lo valida el servidor (solo Dueño).
      {
        path: 'remitos',
        title: titulo('Remitos'),
        loadComponent: () => import('./features/remitos/remitos').then((m) => m.Remitos),
      },
      // Sin guard de rol: la caja la usan el Vendedor y el Dueño; el historial es del Dueño y el Vendedor ve el aviso de la pantalla.
      {
        path: 'caja',
        title: titulo('Caja'),
        loadComponent: () => import('./features/caja/caja').then((m) => m.Caja),
      },
      {
        path: 'caja/historial',
        title: titulo('Historial de cajas'),
        loadComponent: () => import('./features/caja/historial-cajas').then((m) => m.HistorialCajas),
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
      // Sin guard de rol, como las otras pantallas del Dueño: al Vendedor la pantalla le avisa y el backend responde 403.
      {
        path: 'configuracion/cotizacion',
        title: titulo('Cotización'),
        loadComponent: () =>
          import('./features/configuracion-cotizacion/configuracion-cotizacion').then(
            (m) => m.PaginaConfiguracionCotizacion,
          ),
      },
      // Sin guard de rol, como cotización: al Vendedor la pantalla le avisa y el backend responde 403 a escribir.
      {
        path: 'configuracion/formas-pago',
        title: titulo('Formas de pago'),
        loadComponent: () => import('./features/formas-pago/formas-pago').then((m) => m.FormasPago),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
