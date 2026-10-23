import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Route, Router, provideRouter } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { AuthService } from './core/auth/auth.service';
import { inicioPorRol } from './core/auth/inicio-por-rol';
import { ActualizacionMasiva } from './features/actualizacion-masiva/actualizacion-masiva';
import { ArticuloForm } from './features/articulos/articulo-form';
import { Caja } from './features/caja/caja';
import { Compras } from './features/compras/compras';
import { NuevaCompra } from './features/compras/nueva-compra';
import { Reportes } from './features/reportes/reportes';
import { Ventas } from './features/ventas/ventas';
import { CuentaCliente } from './features/clientes/cuenta/cuenta-cliente';
import { HistorialCajas } from './features/caja/historial-cajas';
import { Articulos } from './features/articulos/articulos';
import { PaginaConfiguracionCotizacion } from './features/configuracion-cotizacion/configuracion-cotizacion';
import { FormasPago } from './features/formas-pago/formas-pago';
import { Importaciones } from './features/importaciones/importaciones';
import { Mostrador } from './features/mostrador/mostrador';
import { Proveedores } from './features/proveedores/proveedores';
import { Presupuestos } from './features/presupuestos/presupuestos';
import { Remitos } from './features/remitos/remitos';
import { routes } from './app.routes';

describe('rutas', () => {
  const hijas = (): Route[] => routes.find((r) => r.path === '')?.children ?? [];

  it('la ruta presupuestos carga la pantalla de presupuestos, sin guard de rol: la usan el Vendedor y el Dueño', async () => {
    const padre = routes.find((r) => r.path === '');
    const ruta = hijas().find((r) => r.path === 'presupuestos');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Presupuestos);
    expect(ruta!.canActivate).toBeUndefined();
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta remitos carga la pantalla de remitos, sin guard de rol: la usan el Vendedor y el Dueño (anular lo valida el servidor)', async () => {
    const ruta = hijas().find((r) => r.path === 'remitos');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Remitos);
    expect(ruta!.canActivate).toBeUndefined();
  });

  it('la ruta proveedores carga el componente Proveedores', async () => {
    const ruta = hijas().find((r) => r.path === 'proveedores');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Proveedores);
  });

  it('la ruta importaciones carga el componente Importaciones', async () => {
    const ruta = hijas().find((r) => r.path === 'importaciones');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Importaciones);
  });

  it('las rutas compras y compras/nueva cargan el listado y la carga, sin guard propio (el Vendedor ve el aviso)', async () => {
    const compras = hijas().find((r) => r.path === 'compras');
    const nueva = hijas().find((r) => r.path === 'compras/nueva');

    expect(await (compras!.loadComponent as () => Promise<unknown>)()).toBe(Compras);
    expect(await (nueva!.loadComponent as () => Promise<unknown>)()).toBe(NuevaCompra);
    expect(compras!.canActivate).toBeUndefined();
    expect(nueva!.canActivate).toBeUndefined();
    expect(compras!.title).toBe('Compras · Ferretería');
    expect(nueva!.title).toBe('Nueva compra · Ferretería');
  });

  it('la ruta reportes carga los reportes de ventas, sin guard propio (el Vendedor ve el aviso)', async () => {
    const reportes = hijas().find((r) => r.path === 'reportes');

    expect(await (reportes!.loadComponent as () => Promise<unknown>)()).toBe(Reportes);
    expect(reportes!.canActivate).toBeUndefined();
    expect(reportes!.title).toBe('Reportes · Ferretería');
  });

  it('la ruta ventas carga la consulta de ventas, sin guard propio (el Vendedor ve el aviso)', async () => {
    const ventas = hijas().find((r) => r.path === 'ventas');

    expect(await (ventas!.loadComponent as () => Promise<unknown>)()).toBe(Ventas);
    expect(ventas!.canActivate).toBeUndefined();
    expect(ventas!.title).toBe('Ventas · Ferretería');
  });

  it('la ruta actualizacion-masiva carga el componente ActualizacionMasiva', async () => {
    const ruta = hijas().find((r) => r.path === 'actualizacion-masiva');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(ActualizacionMasiva);
  });

  it('la ruta actualizacion-masiva no tiene guard propio: solo el authGuard del padre, como importaciones', () => {
    const padre = routes.find((r) => r.path === '');
    const masiva = hijas().find((r) => r.path === 'actualizacion-masiva');
    const importaciones = hijas().find((r) => r.path === 'importaciones');

    expect(masiva).toBeDefined();
    expect(masiva!.canActivate).toBeUndefined();
    expect(masiva!.canActivate).toEqual(importaciones!.canActivate);
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta configuracion/cotizacion carga la pantalla de configuración de cotización', async () => {
    const ruta = hijas().find((r) => r.path === 'configuracion/cotizacion');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(PaginaConfiguracionCotizacion);
  });

  it('configuracion/cotizacion no tiene guard propio, como las otras pantallas del Dueño: la pantalla le avisa al Vendedor', () => {
    const padre = routes.find((r) => r.path === '');
    const cotizacion = hijas().find((r) => r.path === 'configuracion/cotizacion');
    const importaciones = hijas().find((r) => r.path === 'importaciones');

    expect(cotizacion).toBeDefined();
    expect(cotizacion!.canActivate).toBeUndefined();
    expect(cotizacion!.canActivate).toEqual(importaciones!.canActivate);
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta configuracion/formas-pago carga la pantalla de formas de pago, sin guard propio (la pantalla le avisa al Vendedor)', async () => {
    const padre = routes.find((r) => r.path === '');
    const ruta = hijas().find((r) => r.path === 'configuracion/formas-pago');
    const importaciones = hijas().find((r) => r.path === 'importaciones');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(FormasPago);
    expect(ruta!.canActivate).toBeUndefined();
    expect(ruta!.canActivate).toEqual(importaciones!.canActivate);
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta articulos carga el componente Articulos', async () => {
    const ruta = hijas().find((r) => r.path === 'articulos');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Articulos);
  });

  it('las rutas articulos/nuevo y articulos/:id cargan el formulario de artículo', async () => {
    for (const path of ['articulos/nuevo', 'articulos/:id']) {
      const ruta = hijas().find((r) => r.path === path);

      expect(ruta, path).toBeDefined();
      expect(await (ruta!.loadComponent as () => Promise<unknown>)(), path).toBe(ArticuloForm);
    }
  });

  it('articulos/nuevo va antes que articulos/:id para que "nuevo" no se tome como id', () => {
    const paths = hijas().map((r) => r.path);

    expect(paths.indexOf('articulos/nuevo')).toBeGreaterThanOrEqual(0);
    expect(paths.indexOf('articulos/nuevo')).toBeLessThan(paths.indexOf('articulos/:id'));
  });

  it('la ruta caja carga la pantalla Caja y caja/historial el historial, sin guard de rol (CU-10)', async () => {
    const caja = hijas().find((r) => r.path === 'caja');
    const historial = hijas().find((r) => r.path === 'caja/historial');

    expect(await (caja!.loadComponent as () => Promise<unknown>)()).toBe(Caja);
    expect(await (historial!.loadComponent as () => Promise<unknown>)()).toBe(HistorialCajas);
    expect(caja!.canActivate).toBeUndefined();
    expect(historial!.canActivate).toBeUndefined();
  });

  it('la ruta clientes/:id/cuenta carga la cuenta del cliente, sin guard de rol: el contenedor maneja «sinPermiso» (CC-043)', async () => {
    const padre = routes.find((r) => r.path === '');
    const ruta = hijas().find((r) => r.path === 'clientes/:id/cuenta');

    expect(ruta).toBeDefined();
    expect(ruta!.path).toContain(':id');
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(CuentaCliente);
    expect(ruta!.canActivate).toBeUndefined();
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta mostrador carga el componente Mostrador', async () => {
    const ruta = hijas().find((r) => r.path === 'mostrador');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Mostrador);
  });

  it('la ruta mostrador no tiene guard de rol: solo el authGuard del padre', () => {
    const padre = routes.find((r) => r.path === '');
    const mostrador = hijas().find((r) => r.path === 'mostrador');

    expect(mostrador).toBeDefined();
    expect(mostrador!.canActivate).toBeUndefined();
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta por defecto redirige según el rol con inicioPorRol', () => {
    const inicio = hijas().find((r) => r.path === '');

    expect(inicio).toBeDefined();
    expect(inicio!.pathMatch).toBe('full');
    expect(inicio!.redirectTo).toBe(inicioPorRol);
  });

  it('cualquier ruta desconocida vuelve a la raíz', () => {
    const comodin = routes.find((r) => r.path === '**');

    expect(comodin?.redirectTo).toBe('');
  });

  it('cada pantalla pone su nombre y el del producto en la pestaña', () => {
    const titulo = (lista: Route[], path: string) => lista.find((r) => r.path === path)?.title;

    expect(titulo(routes, 'login')).toBe('Ingresar · Ferretería');
    expect(titulo(hijas(), 'mostrador')).toBe('Mostrador · Ferretería');
    expect(titulo(hijas(), 'rubros')).toBe('Rubros · Ferretería');
    expect(titulo(hijas(), 'articulos')).toBe('Artículos · Ferretería');
    expect(titulo(hijas(), 'articulos/nuevo')).toBe('Nuevo artículo · Ferretería');
    expect(titulo(hijas(), 'articulos/:id')).toBe('Artículo · Ferretería');
    expect(titulo(hijas(), 'proveedores')).toBe('Proveedores · Ferretería');
    expect(titulo(hijas(), 'importaciones')).toBe('Importar lista · Ferretería');
    expect(titulo(hijas(), 'actualizacion-masiva')).toBe('Actualizar precios · Ferretería');
    expect(titulo(hijas(), 'configuracion/cotizacion')).toBe('Cotización · Ferretería');
    expect(titulo(hijas(), 'configuracion/formas-pago')).toBe('Formas de pago · Ferretería');
    expect(titulo(hijas(), 'clientes/:id/cuenta')).toBe('Cuenta corriente · Ferretería');
  });

  it('sin sesión, /mostrador redirige al login recordando adónde quería ir', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthService, useValue: { autenticado: signal(false) } },
      ],
    });
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/mostrador');

    expect(router.url).toBe('/login?returnUrl=%2Fmostrador');
  });

  it('sin sesión, una URL con id y parámetros vuelve entera después del login', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthService, useValue: { autenticado: signal(false) } },
      ],
    });
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/articulos/5');

    expect(router.parseUrl(router.url).queryParams['returnUrl']).toBe('/articulos/5');
  });

  it('sin sesión, la raíz va al login sin returnUrl', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthService, useValue: { autenticado: signal(false), sesion: signal(null) } },
      ],
    });
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/');

    expect(router.url).toBe('/login');
  });
});
