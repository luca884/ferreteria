import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { NavigationStart, Route, Router, provideRouter } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { Rol, Sesion } from './core/auth/auth.models';
import { AuthService } from './core/auth/auth.service';
import { inicioPorRol } from './core/auth/inicio-por-rol';
import { ActualizacionMasiva } from './features/actualizacion-masiva/actualizacion-masiva';
import { ArticuloForm } from './features/articulos/articulo-form';
import { MovimientosArticulo } from './features/articulos/movimientos-articulo';
import { Caja } from './features/caja/caja';
import { Compras } from './features/compras/compras';
import { NuevaCompra } from './features/compras/nueva-compra';
import { Reportes } from './features/reportes/reportes';
import { StockBajoMinimo } from './features/stock-bajo-minimo/stock-bajo-minimo';
import { ControlesDeDatos } from './features/controles-de-datos/controles-de-datos';
import { Ventas } from './features/ventas/ventas';
import { CuentaCliente } from './features/clientes/cuenta/cuenta-cliente';
import { HistorialCajas } from './features/caja/historial-cajas';
import { Articulos } from './features/articulos/articulos';
import { PaginaConfiguracionCotizacion } from './features/configuracion-cotizacion/configuracion-cotizacion';
import { PaginaConfiguracionPrecios } from './features/configuracion-precios/configuracion-precios';
import { FormasPago } from './features/formas-pago/formas-pago';
import { PaginaConfiguracionVentas } from './features/configuracion-ventas/configuracion-ventas';
import { PaginaDatosFiscales } from './features/datos-fiscales/datos-fiscales';
import { PaginaFacturacion } from './features/facturacion/facturacion';
import { Importaciones } from './features/importaciones/importaciones';
import { Mostrador } from './features/mostrador/mostrador';
import { Proveedores } from './features/proveedores/proveedores';
import { Presupuestos } from './features/presupuestos/presupuestos';
import { Remitos } from './features/remitos/remitos';
import { routes } from './app.routes';

describe('rutas', () => {
  const padre = (): Route => routes.find((r) => r.path === '')!;
  const hijas = (): Route[] => padre().children ?? [];
  const ruta = (path: string): Route => {
    const encontrada = hijas().find((r) => r.path === path);
    expect(encontrada, path).toBeDefined();
    return encontrada!;
  };
  const componente = (path: string) => (ruta(path).loadComponent as () => Promise<unknown>)();

  it.each<[string, unknown]>([
    ['mostrador', Mostrador],
    ['presupuestos', Presupuestos],
    ['presupuestos/:id', Presupuestos],
    ['remitos', Remitos],
    ['remitos/:id', Remitos],
    ['clientes/:id/cuenta', CuentaCliente],
    ['caja', Caja],
    ['caja/historial', HistorialCajas],
    ['articulos', Articulos],
    ['articulos/nuevo', ArticuloForm],
    ['articulos/:id', ArticuloForm],
    ['articulos/:id/movimientos', MovimientosArticulo],
    ['proveedores', Proveedores],
    ['compras', Compras],
    ['compras/nueva', NuevaCompra],
    ['compras/:id', Compras],
    ['ventas', Ventas],
    ['ventas/:id', Ventas],
    ['reportes', Reportes],
    ['stock-bajo-minimo', StockBajoMinimo],
    ['controles-de-datos', ControlesDeDatos],
    ['importaciones', Importaciones],
    ['actualizacion-masiva', ActualizacionMasiva],
    ['configuracion/cotizacion', PaginaConfiguracionCotizacion],
    ['configuracion/precios', PaginaConfiguracionPrecios],
    ['configuracion/ventas', PaginaConfiguracionVentas],
    ['configuracion/datos-fiscales', PaginaDatosFiscales],
    ['configuracion/facturacion', PaginaFacturacion],
    ['configuracion/formas-pago', FormasPago],
  ])('la ruta %s carga su pantalla', async (path, pantalla) => {
    expect(await componente(path)).toBe(pantalla);
  });

  // ---- roles (guards-por-rol, S1 y S2) ----

  const AMBOS: Rol[] = ['DUENO', 'VENDEDOR'];
  const DUENO: Rol[] = ['DUENO'];
  const MAPA: Record<string, Rol[]> = {
    mostrador: AMBOS,
    presupuestos: AMBOS,
    'presupuestos/:id': AMBOS,
    remitos: AMBOS,
    'remitos/:id': AMBOS,
    clientes: AMBOS,
    caja: AMBOS,
    rubros: AMBOS,
    articulos: AMBOS,
    'articulos/:id': AMBOS,
    proveedores: AMBOS,
    'clientes/:id/cuenta': DUENO,
    'caja/historial': DUENO,
    'articulos/nuevo': DUENO,
    'articulos/:id/movimientos': DUENO,
    'articulos/:id/clientes': DUENO,
    compras: DUENO,
    'compras/nueva': DUENO,
    'compras/:id': DUENO,
    ventas: DUENO,
    'ventas/:id': DUENO,
    reportes: DUENO,
    'stock-bajo-minimo': DUENO,
    'controles-de-datos': DUENO,
    etiquetas: DUENO,
    importaciones: DUENO,
    'actualizacion-masiva': DUENO,
    'configuracion/cotizacion': DUENO,
    'configuracion/precios': DUENO,
    'configuracion/ventas': DUENO,
    'configuracion/datos-fiscales': DUENO,
    'configuracion/facturacion': DUENO,
    'configuracion/formas-pago': DUENO,
    usuarios: DUENO,
    'admin/ferreterias': ['SUPER_ADMIN'],
    'mi-contrasena': ['DUENO', 'VENDEDOR', 'SUPER_ADMIN'],
  };

  it('cada pantalla del shell declara exactamente los roles del mapa, y no hay pantallas fuera del mapa', () => {
    const pantallas = hijas().filter((r) => !r.redirectTo);

    expect(pantallas.map((r) => r.path).sort()).toEqual(Object.keys(MAPA).sort());
    for (const r of pantallas) {
      expect([...(r.data?.['roles'] ?? [])].sort(), r.path).toEqual([...MAPA[r.path!]].sort());
    }
  });

  it('el shell exige sesión y, en cada hija, el cambio de contraseña y el rol', () => {
    expect(padre().canActivate).toEqual([authGuard]);
    expect(padre().canActivateChild?.length).toBe(2);
  });

  function navegarComo(rol: Rol, debeCambiarContrasena = false) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        {
          provide: AuthService,
          useValue: {
            autenticado: signal(true),
            debeCambiarContrasena: signal(debeCambiarContrasena),
            sesion: signal({ rol } as Sesion),
          },
        },
      ],
    });
    return TestBed.inject(Router);
  }

  /** Navega y cuenta los NavigationStart: más de 2 sería un loop de redirecciones. */
  async function navegar(router: Router, url: string): Promise<number> {
    let inicios = 0;
    const sub = router.events.subscribe((e) => {
      if (e instanceof NavigationStart) inicios++;
    });
    await router.navigateByUrl(url);
    sub.unsubscribe();
    return inicios;
  }

  const INICIO: Record<Rol, string> = { DUENO: '/mostrador', VENDEDOR: '/mostrador', SUPER_ADMIN: '/admin/ferreterias' };
  const recorrido = Object.entries(MAPA).flatMap(([path, roles]) =>
    (['DUENO', 'VENDEDOR', 'SUPER_ADMIN'] as Rol[]).map((rol) => {
      const url = '/' + path.replace(':id', '7');
      return [rol, url, roles.includes(rol) ? url : INICIO[rol]] as const;
    }),
  );

  it.each(recorrido)('%s en %s termina en %s, sin loop', async (rol, url, esperado) => {
    const router = navegarComo(rol);

    const inicios = await navegar(router, url);

    expect(router.url).toBe(esperado);
    expect(inicios).toBeLessThanOrEqual(2);
  });

  it.each<[Rol, string]>([
    ['DUENO', '/ventas'],
    ['VENDEDOR', '/ventas'],
    ['VENDEDOR', '/admin/ferreterias'],
    ['SUPER_ADMIN', '/mostrador'],
  ])('con el cambio de contraseña pendiente, %s en %s termina en «Mi contraseña», sin loop', async (rol, url) => {
    const router = navegarComo(rol, true);

    const inicios = await navegar(router, url);

    expect(router.url).toBe('/mi-contrasena');
    expect(inicios).toBeLessThanOrEqual(3);
  });

  it.each(['/', '/no-existe'])('el super admin en %s va a Ferreterías', async (url) => {
    const router = navegarComo('SUPER_ADMIN');

    await navegar(router, url);

    expect(router.url).toBe('/admin/ferreterias');
  });

  it.each(['/admin/ferreterias', '/ventas', '/configuracion/facturacion', '/usuarios', '/clientes/3/cuenta'])(
    'el Vendedor que escribe %s vuelve al Mostrador',
    async (url) => {
      const router = navegarComo('VENDEDOR');

      await router.navigateByUrl(url);

      expect(router.url).toBe('/mostrador');
    },
  );

  it.each(['/mostrador', '/articulos/5', '/usuarios'])(
    'el super admin que escribe %s vuelve a Ferreterías',
    async (url) => {
      const router = navegarComo('SUPER_ADMIN');

      await router.navigateByUrl(url);

      expect(router.url).toBe('/admin/ferreterias');
    },
  );

  it('el Dueño que escribe la pantalla del super admin vuelve al Mostrador', async () => {
    const router = navegarComo('DUENO');

    await router.navigateByUrl('/admin/ferreterias');

    expect(router.url).toBe('/mostrador');
  });

  // ---- orden, inicio y comodín ----

  it('articulos/nuevo va antes que articulos/:id para que "nuevo" no se tome como id', () => {
    const paths = hijas().map((r) => r.path);

    expect(paths.indexOf('articulos/nuevo')).toBeGreaterThanOrEqual(0);
    expect(paths.indexOf('articulos/nuevo')).toBeLessThan(paths.indexOf('articulos/:id'));
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
    expect(titulo(hijas(), 'articulos/:id/movimientos')).toBe('Movimientos de stock · Ferretería');
    expect(titulo(hijas(), 'proveedores')).toBe('Proveedores · Ferretería');
    expect(titulo(hijas(), 'importaciones')).toBe('Importar lista · Ferretería');
    expect(titulo(hijas(), 'actualizacion-masiva')).toBe('Actualizar precios · Ferretería');
    expect(titulo(hijas(), 'configuracion/cotizacion')).toBe('Cotización · Ferretería');
    expect(titulo(hijas(), 'configuracion/precios')).toBe('Margen y redondeo · Ferretería');
    expect(titulo(hijas(), 'configuracion/formas-pago')).toBe('Formas de pago · Ferretería');
    expect(titulo(hijas(), 'clientes/:id/cuenta')).toBe('Cuenta corriente · Ferretería');
    expect(titulo(hijas(), 'compras')).toBe('Compras · Ferretería');
    expect(titulo(hijas(), 'compras/nueva')).toBe('Nueva compra · Ferretería');
    expect(titulo(hijas(), 'reportes')).toBe('Reportes · Ferretería');
    expect(titulo(hijas(), 'ventas')).toBe('Ventas · Ferretería');
    expect(titulo(hijas(), 'stock-bajo-minimo')).toBe('Stock bajo mínimo · Ferretería');
    expect(titulo(hijas(), 'controles-de-datos')).toBe('Controles de datos · Ferretería');
    expect(titulo(hijas(), 'configuracion/ventas')).toBe('Consumidor Final · Ferretería');
    expect(titulo(hijas(), 'configuracion/datos-fiscales')).toBe('Datos de la ferretería · Ferretería');
    expect(titulo(hijas(), 'configuracion/facturacion')).toBe('Facturación · Ferretería');
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
