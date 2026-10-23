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
      // Sin guard de rol: los clientes los usan el Vendedor y el Dueño; desactivar lo valida el servidor (solo Dueño).
      {
        path: 'clientes',
        title: titulo('Clientes'),
        loadComponent: () => import('./features/clientes/clientes').then((m) => m.Clientes),
      },
      // Sin guard de rol, como las otras pantallas del Dueño: al Vendedor la pantalla le avisa y no pide nada al servidor.
      {
        path: 'clientes/:id/cuenta',
        title: titulo('Cuenta corriente'),
        loadComponent: () => import('./features/clientes/cuenta/cuenta-cliente').then((m) => m.CuentaCliente),
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
        path: 'articulos/:id/movimientos',
        title: titulo('Movimientos de stock'),
        loadComponent: () =>
          import('./features/articulos/movimientos-articulo').then((m) => m.MovimientosArticulo),
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
      // Sin guard de rol, como importaciones: las compras son del Dueño; el Vendedor ve el aviso y el backend responde 403.
      {
        path: 'compras',
        title: titulo('Compras'),
        loadComponent: () => import('./features/compras/compras').then((m) => m.Compras),
      },
      {
        path: 'compras/nueva',
        title: titulo('Nueva compra'),
        loadComponent: () => import('./features/compras/nueva-compra').then((m) => m.NuevaCompra),
      },
      // Sin guard de rol, como las otras pantallas del Dueño: al Vendedor el API le responde 403 y la pantalla lo avisa sin mostrar datos.
      {
        path: 'ventas',
        title: titulo('Ventas'),
        loadComponent: () => import('./features/ventas/ventas').then((m) => m.Ventas),
      },
      {
        path: 'reportes',
        title: titulo('Reportes'),
        loadComponent: () => import('./features/reportes/reportes').then((m) => m.Reportes),
      },
      {
        path: 'stock-bajo-minimo',
        title: titulo('Stock bajo mínimo'),
        loadComponent: () =>
          import('./features/stock-bajo-minimo/stock-bajo-minimo').then((m) => m.StockBajoMinimo),
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
      // Sin guard de rol, como las otras pantallas del Dueño: al Vendedor el API le responde 403 y la pantalla lo avisa sin mostrar datos.
      {
        path: 'usuarios',
        title: titulo('Usuarios'),
        loadComponent: () => import('./features/usuarios/usuarios').then((m) => m.Usuarios),
      },
      // Sin guard de rol, como las otras pantallas por rol: a quien no es super admin el API le responde 403 y la pantalla no muestra datos.
      {
        path: 'admin/ferreterias',
        title: titulo('Ferreterías'),
        loadComponent: () =>
          import('./features/admin-ferreterias/admin-ferreterias').then((m) => m.AdminFerreterias),
      },
      // Sin guard de rol: «Cambiar mi contraseña» es de todo usuario logueado.
      {
        path: 'mi-contrasena',
        title: titulo('Cambiar mi contraseña'),
        loadComponent: () =>
          import('./features/mi-contrasena/mi-contrasena').then((m) => m.MiContrasena),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
