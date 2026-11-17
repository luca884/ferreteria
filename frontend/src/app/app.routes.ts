import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { contrasenaGuard } from './core/auth/contrasena.guard';
import { inicioPorRol } from './core/auth/inicio-por-rol';
import {
  DUENO_Y_VENDEDOR,
  SOLO_DUENO,
  SOLO_SUPER_ADMIN,
  TODOS_LOS_ROLES,
  conRoles,
  rolGuard,
} from './core/auth/rol.guard';

/** Título de la pestaña: la pantalla primero, para distinguir pestañas abiertas en paralelo. */
const titulo = (pantalla: string) => `${pantalla} · Ferretería`;

/**
 * Cada pantalla del shell declara sus roles (`data: conRoles(...)`) y `rolGuard` los exige: quien no tiene permiso
 * vuelve a su inicio. Es la capa del frontend; el servidor responde 403 por su lado (`SecurityConfig` y
 * `@PreAuthorize`). Una ruta sin roles queda cerrada para todos.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: titulo('Ingresar'),
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivate: [authGuard],
    // Primero el cambio obligatorio de contraseña (todo lleva a «Mi contraseña»), después el rol de cada pantalla.
    canActivateChild: [contrasenaGuard, rolGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: inicioPorRol },
      {
        path: 'mostrador',
        title: titulo('Mostrador'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/mostrador/mostrador').then((m) => m.Mostrador),
      },
      {
        path: 'presupuestos',
        title: titulo('Presupuestos'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/presupuestos/presupuestos').then((m) => m.Presupuestos),
      },
      // Un presupuesto por su id (los links entre documentos y el aviso del mostrador): la misma pantalla con su pop-up.
      {
        path: 'presupuestos/:id',
        title: titulo('Presupuestos'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/presupuestos/presupuestos').then((m) => m.Presupuestos),
      },
      // Anular un remito lo valida el servidor (solo Dueño).
      {
        path: 'remitos',
        title: titulo('Remitos'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/remitos/remitos').then((m) => m.Remitos),
      },
      // Un remito por su id (los links entre documentos y el aviso del mostrador): la misma pantalla con su pop-up.
      {
        path: 'remitos/:id',
        title: titulo('Remitos'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/remitos/remitos').then((m) => m.Remitos),
      },
      // Desactivar un cliente lo valida el servidor (solo Dueño).
      {
        path: 'clientes',
        title: titulo('Clientes'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/clientes/clientes').then((m) => m.Clientes),
      },
      {
        path: 'clientes/:id/cuenta',
        title: titulo('Cuenta corriente'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/clientes/cuenta/cuenta-cliente').then((m) => m.CuentaCliente),
      },
      {
        path: 'caja',
        title: titulo('Caja'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/caja/caja').then((m) => m.Caja),
      },
      {
        path: 'caja/historial',
        title: titulo('Historial de cajas'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/caja/historial-cajas').then((m) => m.HistorialCajas),
      },
      {
        path: 'rubros',
        title: titulo('Rubros'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/rubros/rubros').then((m) => m.Rubros),
      },
      {
        path: 'articulos',
        title: titulo('Artículos'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/articulos/articulos').then((m) => m.Articulos),
      },
      // 'nuevo' va antes que ':id' para que no se tome como un id.
      {
        path: 'articulos/nuevo',
        title: titulo('Nuevo artículo'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'articulos/:id/movimientos',
        title: titulo('Movimientos de stock'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/articulos/movimientos-articulo').then((m) => m.MovimientosArticulo),
      },
      {
        path: 'articulos/:id/clientes',
        title: titulo('Clientes del artículo'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/articulos/clientes-articulo').then((m) => m.ClientesArticulo),
      },
      // La ficha la ve también el Vendedor, sin costos ni margen (H07); editarla es solo del Dueño.
      {
        path: 'articulos/:id',
        title: titulo('Artículo'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/articulos/articulo-form').then((m) => m.ArticuloForm),
      },
      {
        path: 'proveedores',
        title: titulo('Proveedores'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/proveedores/proveedores').then((m) => m.Proveedores),
      },
      {
        path: 'compras',
        title: titulo('Compras'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/compras/compras').then((m) => m.Compras),
      },
      {
        path: 'compras/nueva',
        title: titulo('Nueva compra'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/compras/nueva-compra').then((m) => m.NuevaCompra),
      },
      // El detalle de una compra por su id (link de «Última compra» en la ficha del artículo): la misma pantalla. Va después de «nueva», que no es un id.
      {
        path: 'compras/:id',
        title: titulo('Compras'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/compras/compras').then((m) => m.Compras),
      },
      // La entrada al grupo Ventas de la barra del celular: la ven los dos roles y sólo lista lo que cada uno ya puede abrir.
      {
        path: 'area-ventas',
        title: titulo('Ventas'),
        data: conRoles(DUENO_Y_VENDEDOR),
        loadComponent: () => import('./features/ventas/hub-ventas').then((m) => m.HubVentas),
      },
      {
        path: 'ventas',
        title: titulo('Ventas'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/ventas/ventas').then((m) => m.Ventas),
      },
      // Una venta o nota de crédito por su id (los links entre documentos): la misma pantalla con su pop-up.
      {
        path: 'ventas/:id',
        title: titulo('Ventas'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/ventas/ventas').then((m) => m.Ventas),
      },
      {
        path: 'reportes',
        title: titulo('Reportes'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/reportes/reportes').then((m) => m.Reportes),
      },
      {
        path: 'stock-bajo-minimo',
        title: titulo('Stock bajo mínimo'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/stock-bajo-minimo/stock-bajo-minimo').then((m) => m.StockBajoMinimo),
      },
      {
        path: 'controles-de-datos',
        title: titulo('Controles de datos'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/controles-de-datos/controles-de-datos').then((m) => m.ControlesDeDatos),
      },
      {
        path: 'etiquetas',
        title: titulo('Etiquetas'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/etiquetas/etiquetas').then((m) => m.Etiquetas),
      },
      {
        path: 'importaciones',
        title: titulo('Importar lista'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/importaciones/importaciones').then((m) => m.Importaciones),
      },
      {
        path: 'actualizacion-masiva',
        title: titulo('Actualizar precios'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/actualizacion-masiva/actualizacion-masiva').then(
            (m) => m.ActualizacionMasiva,
          ),
      },
      {
        path: 'configuracion/cotizacion',
        title: titulo('Cotización'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/configuracion-cotizacion/configuracion-cotizacion').then(
            (m) => m.PaginaConfiguracionCotizacion,
          ),
      },
      {
        path: 'configuracion/precios',
        title: titulo('Margen y redondeo'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/configuracion-precios/configuracion-precios').then((m) => m.PaginaConfiguracionPrecios),
      },
      {
        path: 'configuracion/ventas',
        title: titulo('Consumidor Final'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/configuracion-ventas/configuracion-ventas').then((m) => m.PaginaConfiguracionVentas),
      },
      {
        path: 'configuracion/datos-fiscales',
        title: titulo('Datos de la ferretería'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () =>
          import('./features/datos-fiscales/datos-fiscales').then((m) => m.PaginaDatosFiscales),
      },
      {
        path: 'configuracion/facturacion',
        title: titulo('Facturación'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/facturacion/facturacion').then((m) => m.PaginaFacturacion),
      },
      {
        path: 'configuracion/formas-pago',
        title: titulo('Formas de pago'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/formas-pago/formas-pago').then((m) => m.FormasPago),
      },
      {
        path: 'usuarios',
        title: titulo('Usuarios'),
        data: conRoles(SOLO_DUENO),
        loadComponent: () => import('./features/usuarios/usuarios').then((m) => m.Usuarios),
      },
      {
        path: 'admin/ferreterias',
        title: titulo('Ferreterías'),
        data: conRoles(SOLO_SUPER_ADMIN),
        loadComponent: () =>
          import('./features/admin-ferreterias/admin-ferreterias').then((m) => m.AdminFerreterias),
      },
      {
        path: 'mi-contrasena',
        title: titulo('Cambiar mi contraseña'),
        data: conRoles(TODOS_LOS_ROLES),
        loadComponent: () =>
          import('./features/mi-contrasena/mi-contrasena').then((m) => m.MiContrasena),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
