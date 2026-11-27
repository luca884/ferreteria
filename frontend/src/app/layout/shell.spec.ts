import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, WritableSignal, signal } from '@angular/core';
import { Router, Routes, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Sesion } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Avisos } from '../core/avisos/avisos';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../core/cotizacion/cotizacion.service';
import { MenuService } from '../core/menu/menu.service';
import { ClientesService } from '../features/clientes/clientes.service';
import { ProveedoresService } from '../features/proveedores/proveedores.service';
import { DISPOSICION_ORIGINAL, Disposicion } from './menu';
import { Shell } from './shell';

const COTIZACION: Cotizacion = {
  activa: true,
  desactualizada: false,
  ultimaRespuesta: '2026-10-16T12:30:00Z',
  visibles: [
    {
      codigo: 'USD:oficial',
      nombre: 'Oficial',
      compra: 950,
      venta: 1000,
      fuente: 'DOLARAPI',
      actualizadaEn: '2026-10-16T11:00:00Z',
      obtenidaEn: '2026-10-16T12:30:00Z',
      desactualizada: false,
      esDePrecios: true,
    },
  ],
  precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1000, desde: '2026-10-16T11:00:00Z', aConfirmar: null },
};

@Component({ template: '' })
class Vacia {}

/** Cualquier ruta abre una pantalla vacía: alcanza para que el shell sepa en qué sección está. */
const TODAS: Routes = [{ path: '**', component: Vacia }];

/** Pantallas de prueba con el `h1` que tienen todas las pantallas reales: el shell lee de ahí el título. */
@Component({ template: '<h1>Cotización</h1><p>Oficial venta 1.000,00</p>' })
class PantallaCotizacion {}

@Component({ template: '<h1>Clientes</h1>' })
class PantallaClientes {}

/** Una pantalla con un título medio: el más largo de las nueve que el recorrido del celular marcó (móvil-pulgar, T5). */
@Component({ template: '<h1>Stock bajo mínimo</h1>' })
class PantallaStock {}

/** Rutas con pantallas que tienen `h1`: sirven para el título del encabezado y para navegar entre dos títulos. */
const CON_TITULO: Routes = [
  { path: 'configuracion/cotizacion', component: PantallaCotizacion },
  { path: 'clientes', component: PantallaClientes },
  { path: 'stock-bajo-minimo', component: PantallaStock },
  { path: '**', component: Vacia },
];

/**
 * Las reglas del documento que tocan `selector`, con la consulta de medios que las envuelve ('' si no tiene): así se
 * puede afirmar que una regla es sólo del celular y que la base —tablet y escritorio— no se movió. Angular le agrega a
 * cada selector el atributo del componente, por eso la comparación es por `includes`.
 */
function reglasDe(selector: string): { consulta: string; estilo: CSSStyleDeclaration }[] {
  const encontradas: { consulta: string; estilo: CSSStyleDeclaration }[] = [];
  const recorrer = (reglas: CSSRuleList, consulta: string) => {
    Array.from(reglas).forEach((regla) => {
      const media = (regla as CSSMediaRule).media;
      const hijas = (regla as CSSMediaRule).cssRules;
      if (media && hijas) {
        recorrer(hijas, [consulta, media.mediaText].filter(Boolean).join(' '));
        return;
      }
      if ((regla as CSSStyleRule).selectorText?.includes(selector)) {
        encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
      }
    });
  };
  Array.from(document.styleSheets).forEach((hoja) => {
    try {
      recorrer(hoja.cssRules, '');
    } catch {
      // Una hoja que no se deja leer no es la del shell.
    }
  });
  return encontradas;
}

describe('Shell', () => {
  let fixture: ComponentFixture<Shell>;

  let obtener: ReturnType<typeof vi.fn<() => Observable<Cotizacion>>>;

  async function render(
    dueno: boolean,
    sesion: Sesion | null = null,
    rutas: Routes = [],
  ): Promise<HTMLElement> {
    obtener ??= vi.fn(() => of(COTIZACION));
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter(rutas),
        {
          provide: AuthService,
          useValue: {
            esDueno: signal(dueno),
            esSuperAdmin: signal(sesion?.rol === 'SUPER_ADMIN'),
            sesion: signal(sesion),
            token: tokenMenu,
            debeCambiarContrasena: marca,
            logout: () => undefined,
          },
        },
        { provide: CotizacionService, useValue: { obtener, refrescos$: refrescos } },
        { provide: MenuService, useValue: { obtener: obtenerMenu } },
        { provide: ClientesService, useValue: { buscar: () => of({ items: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }) } },
        { provide: ProveedoresService, useValue: { listar: () => of([]) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  async function estable(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function irA(url: string): Promise<void> {
    await TestBed.inject(Router).navigateByUrl(url);
    await estable();
  }

  let refrescos: Subject<void>;
  /** La marca de contraseña por cambiar de la sesión (hallazgo 5); los tests la prenden y la apagan. */
  let marca: WritableSignal<boolean>;
  let tokenMenu: WritableSignal<string | null>;
  let obtenerMenu: ReturnType<typeof vi.fn<() => Observable<Disposicion>>>;

  beforeEach(() => {
    tokenMenu = signal(null);
    obtenerMenu = vi.fn(() => of(DISPOSICION_ORIGINAL));
    obtener = vi.fn(() => of(COTIZACION));
    refrescos = new Subject<void>();
    marca = signal(false);
  });

  // Los enlaces del menú del cajón: la barra de destinos del celular (T1) también tiene enlaces y no es esta lista.
  const enlaces = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('aside nav a')).map((a) => a.textContent?.trim());

  const grupos = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav button.grupo')).map((g) => g.textContent?.trim());

  const boton = (el: HTMLElement, grupo: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('nav button.grupo')).find(
      (g) => g.textContent?.trim() === grupo,
    )!;

  /** Las opciones de un grupo abierto, en orden. */
  const opcionesDe = (el: HTMLElement, grupo: string) => {
    const lista = el.querySelector(`#${boton(el, grupo).getAttribute('aria-controls')}`);
    return Array.from(lista?.querySelectorAll('a') ?? []).map((a) => a.textContent?.trim());
  };

  async function abrirTodos(el: HTMLElement): Promise<void> {
    el.querySelectorAll<HTMLButtonElement>('nav button.grupo[aria-expanded="false"]').forEach((g) => g.click());
    await estable();
  }

  describe('super admin', () => {
    const SUPER_ADMIN: Sesion = {
      userId: 1,
      nombre: 'Admin',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    };

    it('ve solo «Ferreterías» en el menú, que lleva a /admin/ferreterias', async () => {
      const el = await render(false, SUPER_ADMIN);

      expect(enlaces(el)).toEqual(['Ferreterías']);
      expect(el.querySelector('nav a[href="/admin/ferreterias"]')).not.toBeNull();
      expect(el.querySelector('nav .grupo')).toBeNull();
    });

    it('no ve ningún link de ferretería', async () => {
      const el = await render(false, SUPER_ADMIN);

      for (const ruta of ['/mostrador', '/rubros', '/clientes', '/caja', '/articulos', '/usuarios', '/importaciones']) {
        expect(el.querySelector(`a[href="${ruta}"]`), ruta).toBeNull();
      }
    });

    it('ve «Cambiar mi contraseña» en el bloque del usuario', async () => {
      const el = await render(false, SUPER_ADMIN);

      expect(el.querySelector('.usuario a[href="/mi-contrasena"]')?.textContent?.trim()).toBe('Cambiar mi contraseña');
    });

    it('no ve el bloque de cotización ni el shell la pide', async () => {
      const el = await render(false, SUPER_ADMIN);

      expect(el.querySelector('app-cotizacion-lateral')).toBeNull();
      expect(obtener).not.toHaveBeenCalled();
    });

    it('el Dueño y el Vendedor no ven «Ferreterías»', async () => {
      for (const dueno of [false, true]) {
        TestBed.resetTestingModule();
        const el = await render(dueno);
        await abrirTodos(el);

        expect(enlaces(el)).not.toContain('Ferreterías');
        expect(el.querySelector('a[href="/admin/ferreterias"]')).toBeNull();
      }
    });
  });

  function simularMenuMovil(): void {
    vi.stubGlobal('matchMedia', (media: string) => ({
      media, matches: media !== '(min-width: 1024px)',
      addEventListener: vi.fn(), removeEventListener: vi.fn(),
    }));
  }

  describe('menú colapsable de escritorio', () => {
    const USUARIO: Sesion = {
      userId: 7, nombre: 'Dueña', email: 'duena@example.test', rol: 'DUENO',
      ferreteriaId: 3, ferreteriaNombre: 'Tornillo',
    };
    const clavePara = (usuarioId: number) => `menu-lateral-colapsado:${usuarioId}`;
    const clave = clavePara(USUARIO.userId);
    const plegar = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('aside.lateral button.plegar-menu')!;

    const esperarEstado = (el: HTMLElement, colapsado: boolean) => {
      const texto = colapsado ? 'Abrir el menú' : 'Achicar el menú';
      const control = plegar(el);
      expect(el.classList.contains('menu-colapsado')).toBe(colapsado);
      expect(control).not.toBeNull();
      expect(control.getAttribute('aria-expanded')).toBe(String(!colapsado));
      expect(control.getAttribute('aria-label')).toBe(texto);
      expect(control.title).toBe(texto);
      expect(el.querySelector(`#${control.getAttribute('aria-controls')}`)).toBe(el.querySelector('aside.lateral'));
    };

    const limpiar = () => [clave, clavePara(1), clavePara(8)].forEach((k) => localStorage.removeItem(k));
    beforeEach(limpiar);
    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
      limpiar();
    });

    it('sin preferencia arranca ancho y tiene el botón dentro del menú lateral', async () => {
      const el = await render(true, USUARIO);

      esperarEstado(el, false);
      expect(localStorage.getItem(clave)).toBeNull();
    });

    it('el mismo botón achica y vuelve a abrir el menú, guardando cada elección', async () => {
      const el = await render(true, USUARIO);
      const control = plegar(el);
      expect(control).not.toBeNull();

      control.click();
      await estable();
      esperarEstado(el, true);
      expect(localStorage.getItem(clave)).toBe('true');

      control.click();
      await estable();
      esperarEstado(el, false);
      expect(localStorage.getItem(clave)).toBe('false');
    });

    it.each([true, false])('recuerda la preferencia %s al abrir y no la cambia al navegar', async (preferencia) => {
      localStorage.setItem(clave, String(preferencia));
      const el = await render(true, USUARIO, TODAS);

      esperarEstado(el, preferencia);
      await irA('/rubros');
      esperarEstado(el, preferencia);
      expect(localStorage.getItem(clave)).toBe(String(preferencia));
    });

    it('si el navegador no deja leer ni escribir la preferencia, el botón funciona igual', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Sin acceso'); });
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Sin acceso'); });
      const el = await render(true, USUARIO);

      esperarEstado(el, false);
      plegar(el).click();
      await estable();
      esperarEstado(el, true);
    });

    it.each([false, true])('al tocar un grupo en la columna vuelve al menú ancho y lo abre (ya abierto: %s)', async (abierto) => {
      localStorage.setItem(clave, 'true');
      const el = await render(true, USUARIO, TODAS);
      if (abierto) await irA('/proveedores');
      const compras = boton(el, 'Compras');
      expect(compras.getAttribute('aria-expanded')).toBe('false');

      compras.click();
      await estable();
      esperarEstado(el, false);
      expect(localStorage.getItem(clave)).toBe('false');
      expect(compras.getAttribute('aria-expanded')).toBe('true');
      expect(opcionesDe(el, 'Compras')).toEqual(['Proveedores', 'Compras']);
    });

    it('Mostrador y los cinco grupos tienen ícono, nombre y título en la columna', async () => {
      localStorage.setItem(clave, 'true');
      const el = await render(true, USUARIO);
      const mostrador = el.querySelector('aside nav a[href="/mostrador"]')!;
      expect(mostrador.textContent?.trim()).toBe('Mostrador');
      expect(mostrador.getAttribute('title')).toBe('Mostrador');
      expect(mostrador.querySelector('svg[aria-hidden="true"] path')).not.toBeNull();

      const iconos = ['Ventas', 'Artículos', 'Compras', 'Precios', 'Configuración'].map((rotulo) => {
        const grupo = boton(el, rotulo);
        expect(grupo.title).toBe(rotulo);
        const icono = grupo.querySelector('app-icono-menu svg[aria-hidden="true"]');
        expect(icono?.getAttribute('viewBox')).toBe('0 0 16 16');
        expect(icono?.querySelector('path')).not.toBeNull();
        return icono!.innerHTML;
      });
      expect(new Set(iconos).size).toBe(5);
    });

    it('la preferencia es de cada usuario: otro usuario del mismo navegador no la hereda ni la pisa', async () => {
      localStorage.setItem(clave, 'true');
      const el = await render(true, { ...USUARIO, userId: 8 });

      esperarEstado(el, false);
      plegar(el).click();
      await estable();
      esperarEstado(el, true);
      expect(localStorage.getItem(clavePara(8))).toBe('true');
      expect(localStorage.getItem(clave)).toBe('true');
      plegar(el).click();
      await estable();
      expect(localStorage.getItem(clavePara(8))).toBe('false');
      expect(localStorage.getItem(clave)).toBe('true');
    });

    it('sin sesión no lee ni guarda ninguna preferencia, y el botón funciona igual', async () => {
      localStorage.setItem(clave, 'true');
      const guardar = vi.spyOn(Storage.prototype, 'setItem');
      const el = await render(true);

      esperarEstado(el, false);
      plegar(el).click();
      await estable();
      esperarEstado(el, true);
      expect(guardar).not.toHaveBeenCalled();
    });

    it('un grupo propio muestra el ícono genérico, con su rótulo como título y nombre en la columna', async () => {
      localStorage.setItem(clave, 'true');
      tokenMenu.set('sesion-duena');
      obtenerMenu.mockReturnValue(of({
        grupos: [{ id: 'g-1', rotulo: 'Pedidos urgentes', tono: 'ventas', claves: ['caja'] }],
        ocultas: [],
      }));
      const el = await render(true, USUARIO, TODAS);
      const grupo = boton(el, 'Pedidos urgentes');

      expect(grupo.title).toBe('Pedidos urgentes');
      const icono = grupo.querySelector('app-icono-menu svg[aria-hidden="true"]');
      expect(icono?.getAttribute('viewBox')).toBe('0 0 16 16');
      expect(icono?.querySelector('path')).not.toBeNull();
      expect(icono?.querySelector('path')?.getAttribute('d')).toBe('M2.5 4h11M2.5 8h11M2.5 12h11');
    });

    it('la columna del super admin conserva el enlace a Ferreterías y el botón Salir con sus rótulos', async () => {
      localStorage.setItem(clavePara(1), 'true');
      const el = await render(false, {
        userId: 1, nombre: 'Admin', email: 'admin@example.test', rol: 'SUPER_ADMIN',
        ferreteriaId: null, ferreteriaNombre: null,
      });
      expect(enlaces(el)).toEqual(['Ferreterías']);
      const ferreterias = el.querySelector('aside nav a[href="/admin/ferreterias"]')!;
      const salir = el.querySelector('aside .usuario button.salir')!;
      expect(ferreterias.getAttribute('title')).toBe('Ferreterías');
      expect(ferreterias.querySelector('svg[aria-hidden="true"] path')).not.toBeNull();
      expect(salir.textContent?.trim()).toBe('Salir');
      expect(salir.getAttribute('title')).toBe('Salir');
      expect(salir.querySelector('svg[aria-hidden="true"] path')).not.toBeNull();
      expect(el.querySelector('.avatar')?.textContent?.trim()).toBe('A');
    });

    it('una preferencia angosta no altera los grupos ni el primer foco del cajón en el celular', async () => {
      simularMenuMovil();
      localStorage.setItem(clave, 'true');
      const el = await render(true, USUARIO);
      expect(plegar(el)?.disabled).toBe(true);
      el.querySelector<HTMLButtonElement>('header button.abrir-menu')!.click();
      await estable();
      const cajon = el.querySelector('aside.lateral')!;
      cajon.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
      expect(document.activeElement).toBe(cajon.querySelector('app-buscador-menu input'));

      const compras = boton(el, 'Compras');
      compras.click();
      await estable();
      expect(compras.getAttribute('aria-expanded')).toBe('true');
      compras.click();
      await estable();
      expect(compras.getAttribute('aria-expanded')).toBe('false');
      expect(localStorage.getItem(clave)).toBe('true');
    });
  });

  describe('menú agrupado (mejoras-ferreteros-bloque-2, S1)', () => {
    it('las secciones van en el menú lateral', async () => {
      const el = await render(true);

      expect(el.querySelector('aside nav[aria-label="Secciones"]')).not.toBeNull();
    });

    it('el Dueño ve «Mostrador» fijo arriba y los grupos Ventas, Artículos, Compras, Precios y Configuración', async () => {
      const el = await render(true);

      expect(el.querySelector('nav a')?.textContent?.trim()).toBe('Mostrador');
      expect(el.querySelector('nav a')?.getAttribute('href')).toBe('/mostrador');
      expect(grupos(el)).toEqual(['Ventas', 'Artículos', 'Compras', 'Precios', 'Configuración']);
    });

    it('el Dueño ve en cada grupo sus opciones, en orden y con su ruta', async () => {
      const el = await render(true);
      await abrirTodos(el);

      expect(opcionesDe(el, 'Ventas')).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes', 'Ventas', 'Reportes']);
      expect(opcionesDe(el, 'Artículos')).toEqual([
        'Artículos',
        'Rubros',
        'Stock bajo mínimo',
        'Etiquetas',
        'Controles de datos',
      ]);
      expect(opcionesDe(el, 'Compras')).toEqual(['Proveedores', 'Compras']);
      expect(opcionesDe(el, 'Precios')).toEqual(['Importar lista', 'Actualizar precios', 'Margen y redondeo', 'Cotización']);
      expect(opcionesDe(el, 'Configuración')).toEqual([
        'Formas de pago',
        'Consumidor Final',
        'Datos de la ferretería',
        'Facturación',
        'Usuarios',
        'Personalizar menú',
      ]);
      const rutas: Record<string, string> = {
        Mostrador: '/mostrador',
        Presupuestos: '/presupuestos',
        Remitos: '/remitos',
        Caja: '/caja',
        Clientes: '/clientes',
        Ventas: '/ventas',
        Reportes: '/reportes',
        Artículos: '/articulos',
        Rubros: '/rubros',
        'Stock bajo mínimo': '/stock-bajo-minimo',
        Etiquetas: '/etiquetas',
        'Controles de datos': '/controles-de-datos',
        Proveedores: '/proveedores',
        Compras: '/compras',
        'Importar lista': '/importaciones',
        'Actualizar precios': '/actualizacion-masiva',
        'Margen y redondeo': '/configuracion/precios',
        Cotización: '/configuracion/cotizacion',
        'Formas de pago': '/configuracion/formas-pago',
        'Consumidor Final': '/configuracion/ventas',
        'Datos de la ferretería': '/configuracion/datos-fiscales',
        Facturación: '/configuracion/facturacion',
        Usuarios: '/usuarios',
        'Personalizar menú': '/configuracion/menu',
      };
      for (const [rotulo, ruta] of Object.entries(rutas)) {
        expect(el.querySelector(`nav a[href="${ruta}"]`)?.textContent?.trim(), ruta).toBe(rotulo);
      }
      expect(el.querySelectorAll('aside nav a').length).toBe(24);
    });

    it('el Vendedor ve Mostrador, Ventas (Presupuestos, Remitos, Caja, Clientes), Artículos (Artículos, Rubros) y Compras (Proveedores)', async () => {
      const el = await render(false);
      await abrirTodos(el);

      expect(grupos(el)).toEqual(['Ventas', 'Artículos', 'Compras']);
      expect(opcionesDe(el, 'Ventas')).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes']);
      expect(opcionesDe(el, 'Artículos')).toEqual(['Artículos', 'Rubros']);
      expect(opcionesDe(el, 'Compras')).toEqual(['Proveedores']);
      expect(enlaces(el)).toEqual(['Mostrador', 'Presupuestos', 'Remitos', 'Caja', 'Clientes', 'Artículos', 'Rubros', 'Proveedores']);
      for (const ruta of [
        '/ventas',
        '/reportes',
        '/stock-bajo-minimo',
        '/etiquetas',
        '/controles-de-datos',
        '/compras',
        '/importaciones',
        '/actualizacion-masiva',
        '/configuracion/precios',
        '/configuracion/cotizacion',
        '/configuracion/formas-pago',
        '/configuracion/ventas',
        '/configuracion/datos-fiscales',
        '/configuracion/facturacion',
        '/configuracion/menu',
        '/usuarios',
      ]) {
        expect(el.querySelector(`a[href="${ruta}"]`), ruta).toBeNull();
      }
    });

    it('fuera de una sección los grupos arrancan cerrados: solo se ve «Mostrador»', async () => {
      const el = await render(true);

      expect(enlaces(el)).toEqual(['Mostrador']);
      for (const grupo of grupos(el)) {
        expect(boton(el, grupo!).getAttribute('aria-expanded'), grupo).toBe('false');
      }
    });

    it('un clic en el grupo lo abre y otro lo cierra', async () => {
      const el = await render(true);
      const compras = boton(el, 'Compras');

      compras.click();
      await estable();
      expect(compras.getAttribute('aria-expanded')).toBe('true');
      expect(enlaces(el)).toEqual(['Mostrador', 'Proveedores', 'Compras']);

      compras.click();
      await estable();
      expect(compras.getAttribute('aria-expanded')).toBe('false');
      expect(enlaces(el)).toEqual(['Mostrador']);
    });

    it('el grupo de la pantalla abierta arranca abierto y su opción se marca con aria-current="page"', async () => {
      const el = await render(true, null, TODAS);

      await irA('/rubros');

      expect(boton(el, 'Artículos').getAttribute('aria-expanded')).toBe('true');
      expect(boton(el, 'Ventas').getAttribute('aria-expanded')).toBe('false');
      expect(el.querySelector('nav a[href="/rubros"]')!.getAttribute('aria-current')).toBe('page');
      expect(el.querySelector('nav a[href="/mostrador"]')!.hasAttribute('aria-current')).toBe(false);
    });

    it('una pantalla interna abre el grupo de su sección (la cuenta de un cliente abre Ventas)', async () => {
      const el = await render(true, null, TODAS);

      await irA('/clientes/5/cuenta');

      expect(boton(el, 'Ventas').getAttribute('aria-expanded')).toBe('true');
    });

    it('cada grupo controla su lista de opciones', async () => {
      const el = await render(true);
      await abrirTodos(el);

      for (const grupo of grupos(el)) {
        const id = boton(el, grupo!).getAttribute('aria-controls');
        expect(id, grupo).toBeTruthy();
        expect(el.querySelector(`#${id}`), grupo).not.toBeNull();
      }
    });
  });

  it('el buscador va arriba de las secciones, para el Dueño y el Vendedor; el super admin no lo tiene (bloque 2, S3)', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      const buscador = el.querySelector('aside app-buscador-menu');
      expect(buscador, dueno ? 'Dueño' : 'Vendedor').not.toBeNull();
      expect(buscador!.compareDocumentPosition(el.querySelector('aside nav')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }

    TestBed.resetTestingModule();
    const admin = await render(false, {
      userId: 1,
      nombre: 'Admin',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    });
    expect(admin.querySelector('app-buscador-menu')).toBeNull();
  });

  describe('color por sección (mejoras-ferreteros-bloque-2, S2)', () => {
    it.each([
      ['/mostrador', 'mostrador'],
      ['/presupuestos', 'ventas'],
      ['/clientes/5/cuenta', 'ventas'],
      ['/rubros', 'articulos'],
      ['/articulos/nuevo', 'articulos'],
      ['/compras/nueva', 'compras'],
      ['/configuracion/cotizacion', 'precios'],
      ['/configuracion/ventas', 'configuracion'],
      ['/usuarios', 'configuracion'],
    ])('en %s el shell lleva la sección «%s»', async (url, seccion) => {
      const el = await render(true, null, TODAS);

      await irA(url);

      expect(el.getAttribute('data-seccion')).toBe(seccion);
    });

    it('una pantalla fuera del menú (Cambiar mi contraseña) no lleva sección', async () => {
      const el = await render(true, null, TODAS);

      await irA('/mi-contrasena');

      expect(el.hasAttribute('data-seccion')).toBe(false);
    });

    it('cada grupo lleva su sección, para tomar su tono', async () => {
      const el = await render(true);

      expect(
        Array.from(el.querySelectorAll('nav button.grupo')).map((g) => g.getAttribute('data-seccion')),
      ).toEqual(['ventas', 'articulos', 'compras', 'precios', 'configuracion']);
    });
  });

  it('todo rol ve «Cambiar mi contraseña» en el bloque del usuario, que lleva a /mi-contrasena', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      const enlace = el.querySelector('.usuario a[href="/mi-contrasena"]');
      expect(enlace).not.toBeNull();
      expect(enlace!.textContent?.trim()).toBe('Cambiar mi contraseña');
    }
  });

  it.each([true, false])('muestra el enlace de contraseña solo si cuentaDemo no está prendida (%s)', async (cuentaDemo) => {
    const sesion = {
      userId: 2,
      nombre: 'Dueño de prueba',
      email: 'dueno@prueba.test',
      rol: 'DUENO' as const,
      ferreteriaId: 1,
      ferreteriaNombre: 'Ferretería de prueba',
      cuentaDemo,
    };
    const el = await render(true, sesion);
    const enlace = el.querySelector('.usuario a[href="/mi-contrasena"]');

    if (cuentaDemo) {
      expect(enlace).toBeNull();
    } else {
      expect(enlace?.textContent?.trim()).toBe('Cambiar mi contraseña');
    }
    expect(el.querySelector('.usuario button.salir')?.textContent?.trim()).toBe('Salir');
  });

  it('el primer elemento es el enlace «Saltar al contenido», que lleva el foco al contenido', async () => {
    const el = await render(true);

    const saltar = el.querySelector('a') as HTMLAnchorElement;
    expect(saltar.textContent?.trim()).toBe('Saltar al contenido');
    expect(saltar.getAttribute('href')).toBe('#contenido');

    const evento = new MouseEvent('click', { bubbles: true, cancelable: true });
    saltar.dispatchEvent(evento);

    const main = el.querySelector('main') as HTMLElement;
    expect(main.id).toBe('contenido');
    expect(main.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(main);
    // Lo resuelve el enlace: el router no navega a un fragmento.
    expect(evento.defaultPrevented).toBe(true);
  });

  it('un aviso de acción que aparece en el contenido se trae a la vista (responsive, S8)', async () => {
    // jsdom no implementa scrollIntoView: se reemplaza por un espía para ver a quién se lo llama y con qué opciones.
    const scroll = vi.fn<(opciones?: ScrollIntoViewOptions) => void>();
    const prototipo = Element.prototype as unknown as { scrollIntoView?: () => void };
    Object.defineProperty(Element.prototype, 'scrollIntoView', { value: scroll, configurable: true, writable: true });
    try {
      const el = await render(true, null, TODAS);
      const contenido = el.querySelector('main') as HTMLElement;
      const aviso = document.createElement('p');
      aviso.className = 'alerta exito';
      aviso.setAttribute('role', 'status');
      aviso.textContent = 'Se guardó «Corralón Sur».';

      contenido.appendChild(aviso);

      await vi.waitFor(() => expect(scroll.mock.contexts).toEqual([aviso]));
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
    } finally {
      delete prototipo.scrollIntoView;
    }
  });

  it('muestra arriba del contenido el aviso que dejó la pantalla anterior, y lo saca en la navegación siguiente (arreglos-confusos, S8a)', async () => {
    const el = await render(true, null, TODAS);
    TestBed.inject(Avisos).dejar('Cambiaste tu contraseña.');

    await irA('/mostrador');

    const aviso = el.querySelector('main .aviso-al-llegar');
    expect(aviso?.textContent?.trim()).toBe('Cambiaste tu contraseña.');
    expect(aviso?.getAttribute('role')).toBe('status');
    expect(aviso?.classList.contains('exito')).toBe(true);
    expect(aviso?.classList.contains('advertencia')).toBe(false);

    await irA('/ventas');

    expect(el.querySelector('.aviso-al-llegar')).toBeNull();
  });

  it('un aviso que llega sin cambiar de pantalla (navegación a la misma URL) se muestra ahí y no queda para la siguiente', async () => {
    const el = await render(true, null, TODAS);
    await irA('/mostrador');
    TestBed.inject(Avisos).dejar('No tenés acceso a Ventas.', 'advertencia');

    await irA('/mostrador');

    expect(el.querySelector('main .aviso-al-llegar.advertencia')?.textContent?.trim()).toBe('No tenés acceso a Ventas.');
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });

  it('volver a la misma URL sin aviso pendiente no borra el que se está mostrando', async () => {
    const el = await render(true, null, TODAS);
    TestBed.inject(Avisos).dejar('Cambiaste tu contraseña.');
    await irA('/mostrador');

    await irA('/mostrador');

    expect(el.querySelector('main .aviso-al-llegar')?.textContent?.trim()).toBe('Cambiaste tu contraseña.');
  });

  it('un aviso de advertencia (el de «No tenés acceso a …») sale en amarillo, no en el verde de éxito', async () => {
    const el = await render(true, null, TODAS);
    TestBed.inject(Avisos).dejar('No tenés acceso a Ventas.', 'advertencia');

    await irA('/mostrador');

    const aviso = el.querySelector('main .aviso-al-llegar');
    expect(aviso?.textContent?.trim()).toBe('No tenés acceso a Ventas.');
    expect(aviso?.getAttribute('role')).toBe('status');
    expect(aviso?.classList.contains('advertencia')).toBe(true);
    expect(aviso?.classList.contains('exito')).toBe(false);
  });

  it('sin aviso pendiente no muestra nada', async () => {
    const el = await render(true, null, TODAS);

    await irA('/mostrador');

    expect(el.querySelector('.aviso-al-llegar')).toBeNull();
  });

  it('el contenido está posicionado: el `caption.sr-only` (position:absolute) no se escapa y estira el documento (arreglos-confusos, S1)', async () => {
    const el = await render(true);

    const main = el.querySelector('main') as HTMLElement;
    expect(getComputedStyle(main).position).toBe('relative');
  });

  it('muestra la ferretería, el usuario, sus iniciales y su rol', async () => {
    const el = await render(false, {
      userId: 2,
      nombre: 'Juan Pérez',
      email: 'vendedor@tornillo.demo',
      rol: 'VENDEDOR',
      ferreteriaId: 1,
      ferreteriaNombre: 'El Tornillo',
    });

    expect(el.querySelector('.ferreteria')?.textContent?.trim()).toBe('El Tornillo');
    expect(el.querySelector('.usuario-nombre')?.textContent?.trim()).toBe('Juan Pérez');
    expect(el.querySelector('.usuario-rol')?.textContent?.trim()).toBe('Vendedor');
    const avatar = el.querySelector('.avatar');
    expect(avatar?.textContent?.trim()).toBe('JP');
    expect(avatar?.getAttribute('aria-hidden')).toBe('true');
  });

  it('con un solo nombre las iniciales son una letra', async () => {
    const el = await render(false, {
      userId: 1,
      nombre: 'ana',
      email: 'a@demo',
      rol: 'VENDEDOR',
      ferreteriaId: 1,
      ferreteriaNombre: 'El Tornillo',
    });

    expect(el.querySelector('.avatar')?.textContent?.trim()).toBe('A');
  });

  it.each([
    ['DUENO', 'Dueño'],
    ['SUPER_ADMIN', 'Administrador'],
  ] as const)('el rol %s se muestra como «%s»', async (rol, etiqueta) => {
    const el = await render(rol === 'DUENO', {
      userId: 1,
      nombre: 'Ana',
      email: 'a@demo',
      rol,
      ferreteriaId: rol === 'DUENO' ? 1 : null,
      ferreteriaNombre: rol === 'DUENO' ? 'El Tornillo' : null,
    });

    expect(el.querySelector('.usuario-rol')?.textContent?.trim()).toBe(etiqueta);
  });

  it('sin ferretería (administrador) dice «Panel de administración»', async () => {
    const el = await render(false, {
      userId: 1,
      nombre: 'Admin',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    });

    expect(el.querySelector('.ferreteria')?.textContent?.trim()).toBe('Panel de administración');
  });

  describe('cotización en el menú lateral', () => {
    const MINUTO = 60_000;
    const texto = (e: Element | null) => e?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

    async function mostrar(el: HTMLElement) {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      return el;
    }

    afterEach(() => vi.useRealTimers());

    it('todos los roles ven el bloque de cotización, debajo de las secciones', async () => {
      for (const dueno of [false, true]) {
        TestBed.resetTestingModule();
        obtener = vi.fn(() => of(COTIZACION));
        const el = await mostrar(await render(dueno));

        const lateral = el.querySelector('aside app-cotizacion-lateral');
        expect(lateral, dueno ? 'Dueño' : 'Vendedor').not.toBeNull();
        expect(texto(lateral)).toContain('Oficial venta 1.000,00');
        const nav = el.querySelector('aside nav')!;
        expect(nav.compareDocumentPosition(lateral!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      }
    });

    it('el link «Configurar» es solo del Dueño', async () => {
      const dueno = await mostrar(await render(true));
      expect(texto(dueno.querySelector('app-cotizacion-lateral a'))).toBe('Configurar');

      TestBed.resetTestingModule();
      obtener = vi.fn(() => of(COTIZACION));
      const vendedor = await mostrar(await render(false));
      expect(vendedor.querySelector('app-cotizacion-lateral a')).toBeNull();
      expect(texto(vendedor.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');
    });

    it('pide la cotización al abrir una vez, no a los 4 minutos y sí a los 5', async () => {
      vi.useFakeTimers();
      await render(true);
      expect(obtener).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(4 * MINUTO);
      expect(obtener).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(1 * MINUTO);
      expect(obtener).toHaveBeenCalledTimes(2);

      vi.advanceTimersByTime(5 * MINUTO);
      expect(obtener).toHaveBeenCalledTimes(3);
    });

    it('lo nuevo que trae el pedido de los 5 minutos reemplaza lo que se ve', async () => {
      vi.useFakeTimers();
      const actualizada: Cotizacion = {
        ...COTIZACION,
        visibles: [{ ...COTIZACION.visibles[0], venta: 1100 }], precios: { ...COTIZACION.precios, valor: 1100 },
      };
      obtener = vi.fn().mockReturnValueOnce(of(COTIZACION)).mockReturnValue(of(actualizada));
      const el = await mostrar(await render(false));
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');

      vi.advanceTimersByTime(5 * MINUTO);
      await mostrar(el);

      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.100,00');
    });

    it('hallazgo 5: con la contraseña por cambiar no la pide (el servidor da 403) ni la muestra, y la pide en cuanto se cambia (arreglos-confusos, S8c)', async () => {
      vi.useFakeTimers();
      marca.set(true);
      const el = await mostrar(await render(false));
      expect(obtener).not.toHaveBeenCalled();
      expect(el.querySelector('app-cotizacion-lateral')).toBeNull();

      vi.advanceTimersByTime(5 * MINUTO);
      expect(obtener).not.toHaveBeenCalled();

      marca.set(false);
      await mostrar(el);

      expect(obtener).toHaveBeenCalledTimes(1);
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');
    });

    it('al destruir el shell se frena el intervalo: no vuelve a pedir', async () => {
      vi.useFakeTimers();
      await render(true);
      expect(obtener).toHaveBeenCalledTimes(1);

      fixture.destroy();
      vi.advanceTimersByTime(30 * MINUTO);

      expect(obtener).toHaveBeenCalledTimes(1);
      expect(vi.getTimerCount()).toBe(0);
    });

    it('si falla un pedido conserva el último valor y sigue pidiendo a los 5 minutos', async () => {
      vi.useFakeTimers();
      obtener = vi
        .fn()
        .mockReturnValueOnce(of(COTIZACION))
        .mockReturnValueOnce(throwError(() => new Error('sin red')))
        .mockReturnValue(of({ ...COTIZACION, visibles: [{ ...COTIZACION.visibles[0], venta: 1200 }], precios: { ...COTIZACION.precios, valor: 1200 } }));
      const el = await mostrar(await render(false));

      vi.advanceTimersByTime(5 * MINUTO);
      await mostrar(el);
      expect(obtener).toHaveBeenCalledTimes(2);
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');

      vi.advanceTimersByTime(5 * MINUTO);
      await mostrar(el);
      expect(obtener).toHaveBeenCalledTimes(3);
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.200,00');
    });

    it('si el primer pedido falla, el bloque dice que no hay datos y el siguiente lo corrige', async () => {
      vi.useFakeTimers();
      obtener = vi.fn().mockReturnValueOnce(throwError(() => new Error('sin red'))).mockReturnValue(of(COTIZACION));
      const el = await mostrar(await render(false));
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Sin datos de cotización por ahora.');

      vi.advanceTimersByTime(5 * MINUTO);
      await mostrar(el);

      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');
    });

    it('cuando el servicio avisa que algo cambió vuelve a pedir enseguida, sin esperar los 5 minutos', async () => {
      vi.useFakeTimers();
      const actualizada: Cotizacion = {
        ...COTIZACION,
        visibles: [{ ...COTIZACION.visibles[0], venta: 1100 }], precios: { ...COTIZACION.precios, valor: 1100 },
      };
      obtener = vi.fn().mockReturnValueOnce(of(COTIZACION)).mockReturnValue(of(actualizada));
      const el = await mostrar(await render(true));
      expect(obtener).toHaveBeenCalledTimes(1);

      refrescos.next();
      await mostrar(el);

      expect(obtener).toHaveBeenCalledTimes(2);
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.100,00');
    });

    it('tras un aviso sigue pidiendo cada 5 minutos', async () => {
      vi.useFakeTimers();
      await render(true);

      refrescos.next();
      expect(obtener).toHaveBeenCalledTimes(2);

      vi.advanceTimersByTime(5 * MINUTO);
      expect(obtener).toHaveBeenCalledTimes(3);
    });

    it('si el pedido del aviso falla conserva lo que se veía', async () => {
      vi.useFakeTimers();
      obtener = vi.fn().mockReturnValueOnce(of(COTIZACION)).mockReturnValue(throwError(() => new Error('sin red')));
      const el = await mostrar(await render(true));

      refrescos.next();
      await mostrar(el);

      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.000,00');
    });

    it('al destruir el shell deja de escuchar los avisos', async () => {
      vi.useFakeTimers();
      await render(true);

      fixture.destroy();
      refrescos.next();

      expect(obtener).toHaveBeenCalledTimes(1);
    });

    it('un pedido lento no pisa a uno más nuevo: se descarta el que quedó atrás', async () => {
      vi.useFakeTimers();
      const lento = new Subject<Cotizacion>();
      obtener = vi.fn().mockReturnValueOnce(lento).mockReturnValue(of({ ...COTIZACION, visibles: [{ ...COTIZACION.visibles[0], venta: 1300 }], precios: { ...COTIZACION.precios, valor: 1300 } }));
      const el = await mostrar(await render(false));

      vi.advanceTimersByTime(5 * MINUTO);
      await mostrar(el);
      lento.next(COTIZACION);
      lento.complete();
      await mostrar(el);

      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Oficial venta 1.300,00');
    });
  });

  describe('menú en cajón (responsive, S2)', () => {
    /** Los controles que pueden recibir el foco, en el orden en que los recorre Tab. */
    const focosables = (raiz: Element): HTMLElement[] =>
      Array.from(
        raiz.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );

    const botonMenu = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('header .abrir-menu');

    const cajonDe = (el: HTMLElement, boton: HTMLButtonElement) =>
      el.querySelector<HTMLElement>(`#${boton.getAttribute('aria-controls')}`);

    const tecla = (key: string, shiftKey = false) =>
      new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });

    /** Abre el cajón con el botón del encabezado y devuelve las dos piezas. */
    async function abrirElCajon(el: HTMLElement): Promise<{ boton: HTMLButtonElement; cajon: HTMLElement }> {
      const boton = botonMenu(el);
      expect(boton, 'el encabezado con el botón «Menú»').not.toBeNull();
      const cajon = cajonDe(el, boton!);
      expect(cajon, 'el cajón que controla el botón').not.toBeNull();
      boton!.click();
      await estable();
      return { boton: boton!, cajon: cajon! };
    }

    /** Deja correr el `MutationObserver` del título (microtarea) y vuelve a estabilizar la vista. */
    async function despuesDeObservar(): Promise<void> {
      await new Promise((resolver) => setTimeout(resolver, 0));
      await estable();
    }

    it('el botón «Menú» del encabezado abre y cierra el cajón', async () => {
      const el = await render(true);
      const boton = botonMenu(el)!;

      expect(boton.textContent?.trim()).toBe('Menú');
      expect(boton.getAttribute('aria-expanded')).toBe('false');
      const cajon = cajonDe(el, boton)!;
      expect(cajon.classList.contains('abierto')).toBe(false);

      const { boton: abierto, cajon: panel } = await abrirElCajon(el);

      expect(abierto.getAttribute('aria-expanded')).toBe('true');
      expect(panel.classList.contains('abierto')).toBe(true);

      abierto.click();
      await estable();

      expect(abierto.getAttribute('aria-expanded')).toBe('false');
      expect(panel.classList.contains('abierto')).toBe(false);
    });

    it('el buscador, los grupos, la cotización y el bloque de usuario siguen dentro del cajón', async () => {
      const el = await render(true);
      const { cajon } = await abrirElCajon(el);

      expect(cajon.querySelector('app-buscador-menu')).not.toBeNull();
      expect(cajon.querySelector('nav[aria-label="Secciones"]')).not.toBeNull();
      expect(cajon.querySelector('app-cotizacion-lateral')).not.toBeNull();
      expect(cajon.querySelector('.usuario')).not.toBeNull();
      expect(cajon.querySelector('.usuario a[href="/mi-contrasena"]')).not.toBeNull();
      // La pantalla queda fuera del cajón: no se mueve con él.
      expect(el.querySelector('main')!.contains(cajon)).toBe(false);
    });

    it('el cajón se cierra al navegar a otra pantalla', async () => {
      const el = await render(true, null, TODAS);
      const { boton, cajon } = await abrirElCajon(el);

      expect(cajon.classList.contains('abierto')).toBe(true);

      await irA('/ventas');

      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);
    });

    it('el cajón se cierra al tocar la opción de la pantalla en la que ya se está', async () => {
      const el = await render(true, null, TODAS);
      await irA('/ventas');
      const { boton, cajon } = await abrirElCajon(el);

      cajon.querySelector<HTMLAnchorElement>('nav a[href="/ventas"]')!.click();
      await estable();

      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);
    });

    it('Esc cierra el cajón y devuelve el foco al botón «Menú»', async () => {
      const el = await render(true);
      const { boton, cajon } = await abrirElCajon(el);
      expect(cajon.contains(document.activeElement)).toBe(true);

      (document.activeElement as HTMLElement).dispatchEvent(tecla('Escape'));
      await estable();

      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);
      expect(document.activeElement).toBe(boton);
    });

    it('tocar el velo cierra el cajón y devuelve el foco al botón «Menú»', async () => {
      const el = await render(true);
      const { boton, cajon } = await abrirElCajon(el);
      const velo = el.querySelector<HTMLElement>('.velo')!;

      expect(velo, 'el velo detrás del cajón').not.toBeNull();
      expect(velo.classList.contains('abierto')).toBe(true);

      velo.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await estable();

      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);
      expect(velo.classList.contains('abierto')).toBe(false);
      expect(document.activeElement).toBe(boton);
    });

    it('al abrirse el foco queda dentro del cajón', async () => {
      const el = await render(true);
      const { cajon } = await abrirElCajon(el);

      expect(cajon.contains(document.activeElement)).toBe(true);
    });

    it('Tab no se escapa del cajón: del último control vuelve al primero, y con Shift+Tab al revés', async () => {
      // jsdom no aplica el corte CSS: simulamos el ancho donde existe el cajón y no se tabula el botón de escritorio.
      simularMenuMovil();
      try {
        const el = await render(true);
        const { cajon } = await abrirElCajon(el);
        const controles = focosables(cajon);
        const primero = controles[0];
        const ultimo = controles[controles.length - 1];
        expect(controles.length).toBeGreaterThan(1);

        ultimo.focus();
        const tab = tecla('Tab');
        ultimo.dispatchEvent(tab);
        await estable();

        expect(tab.defaultPrevented).toBe(true);
        expect(document.activeElement).toBe(primero);

        primero.focus();
        const shiftTab = tecla('Tab', true);
        primero.dispatchEvent(shiftTab);
        await estable();

        expect(shiftTab.defaultPrevented).toBe(true);
        expect(document.activeElement).toBe(ultimo);
      } finally {
        vi.unstubAllGlobals();
      }
    });

    it('con el cajón cerrado Tab no se intercepta', async () => {
      const el = await render(true);
      const controles = focosables(el.querySelector('aside.lateral')!);
      controles[controles.length - 1].focus();
      const tab = tecla('Tab');
      controles[controles.length - 1].dispatchEvent(tab);

      expect(tab.defaultPrevented).toBe(false);
    });

    it('el encabezado muestra el título de la pantalla y lo cambia al navegar', async () => {
      const el = await render(true, null, CON_TITULO);
      const titulo = () => el.querySelector('.encabezado .titulo-pantalla')?.textContent?.trim();

      await irA('/configuracion/cotizacion');
      await despuesDeObservar();
      expect(titulo()).toBe('Cotización');

      await irA('/clientes');
      await despuesDeObservar();
      expect(titulo()).toBe('Clientes');
    });

    it('en el celular el título puede ocupar dos líneas en vez de recortarse a una', async () => {
      const el = await render(true, null, CON_TITULO);
      await irA('/stock-bajo-minimo');
      await despuesDeObservar();

      // El título se lee entero en el encabezado, que en el celular es la única línea visible del título.
      const titulo = el.querySelector('.encabezado .titulo-pantalla')!;
      expect(titulo.textContent?.trim()).toBe('Stock bajo mínimo');

      // Y la clase que lleva el título tiene la regla del celular: hasta dos líneas, en vez de una sola con puntos
      // suspensivos. La base (tablet y escritorio) queda como está.
      const reglas = reglasDe('titulo-pantalla');
      const base = reglas.find((regla) => regla.consulta === '');
      const celular = reglas.find((regla) => regla.consulta.includes('max-width: 639.98px'));
      expect(base?.estilo.getPropertyValue('white-space')).toBe('nowrap');
      expect(celular?.estilo.getPropertyValue('-webkit-line-clamp')).toBe('2');
      expect(celular?.estilo.getPropertyValue('white-space')).toBe('normal');
    });

    it('el título cubre sin h1, dos h1 y un cambio sin navegar', async () => {
      const el = await render(true, null, CON_TITULO);
      const titulo = () => el.querySelector('.encabezado .titulo-pantalla')?.textContent?.trim();

      await irA('/configuracion/cotizacion');
      await despuesDeObservar();
      el.querySelector('h1')!.textContent = 'Cambiado';
      await despuesDeObservar();
      expect(titulo()).toBe('Cambiado');

      await irA('/ventas');
      await despuesDeObservar();
      expect(titulo()).toBe('');
    });

    // Hallazgo B1 de la verificación independiente: al abrir, el foco queda en el propio cajón (no en un control),
    // así que Shift+Tab tiene que ir al último control de adentro y no salirse al botón «Menú», que está tras el velo.
    it('Shift+Tab con el foco en el cajón recién abierto lo manda al último control, no afuera', async () => {
      const el = await render(true);
      const { cajon } = await abrirElCajon(el);
      const controles = focosables(cajon);
      expect(document.activeElement).toBe(cajon);

      const shiftTab = tecla('Tab', true);
      cajon.dispatchEvent(shiftTab);
      await estable();

      expect(shiftTab.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(controles[controles.length - 1]);
    });
  });

  describe('barra de destinos al pulgar (móvil-pulgar, T1)', () => {
    const SUPER_ADMIN: Sesion = {
      userId: 1,
      nombre: 'Admin',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    };

    const barra = (el: HTMLElement) => el.querySelector<HTMLElement>('nav[aria-label="Destinos"]');

    /** Los destinos de la barra, en orden: los enlaces y, al final, «Más». */
    const destinos = (el: HTMLElement) =>
      Array.from(barra(el)?.querySelectorAll('a, button') ?? []).map((destino) =>
        destino.textContent?.trim(),
      );

    const mas = (el: HTMLElement) => barra(el)?.querySelector<HTMLButtonElement>('button.mas');

    it('el Dueño tiene la barra con Mostrador, Ventas, Artículos y «Más», con nombre accesible', async () => {
      const el = await render(true);

      const nav = barra(el);
      expect(nav, 'la barra de destinos').not.toBeNull();
      expect(nav!.getAttribute('aria-label')).toBe('Destinos');
      expect(destinos(el)).toEqual(['Mostrador', 'Ventas', 'Artículos', 'Más']);
      expect(Array.from(nav!.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual([
        '/mostrador',
        '/area-ventas',
        '/articulos',
      ]);
    });

    it('con el menú original, la barra completa del Dueño conserva sus rótulos y rutas', async () => {
      const el = await render(true);

      expect({
        rotulos: destinos(el),
        hrefs: Array.from(barra(el)!.querySelectorAll('a'), (a) => a.getAttribute('href')),
      }).toEqual({
        rotulos: ['Mostrador', 'Ventas', 'Artículos', 'Más'],
        hrefs: ['/mostrador', '/area-ventas', '/articulos'],
      });
    });

    it('el destino de la pantalla abierta lleva aria-current="page" y los demás no', async () => {
      const el = await render(true, null, TODAS);
      const actual = () => barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href');

      await irA('/ventas');

      expect(actual()).toBe('/area-ventas');
      expect(barra(el)!.querySelector('a[href="/mostrador"]')!.hasAttribute('aria-current')).toBe(
        false,
      );
      expect(barra(el)!.querySelector('a[href="/articulos"]')!.hasAttribute('aria-current')).toBe(
        false,
      );

      await irA('/articulos/12');

      expect(actual()).toBe('/articulos');
      expect(barra(el)!.querySelector('a[href="/area-ventas"]')!.hasAttribute('aria-current')).toBe(
        false,
      );
    });

    it('«Artículos» queda marcado también en Rubros y en las pantallas de su grupo', async () => {
      const el = await render(true, null, TODAS);
      const actual = () => barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href');

      for (const ruta of ['/articulos', '/rubros', '/etiquetas', '/stock-bajo-minimo']) {
        await irA(ruta);
        expect(actual(), ruta).toBe('/articulos');
      }
    });

    it('«Ventas» queda marcado en cualquier pantalla del grupo, no sólo en el hub', async () => {
      const el = await render(true, null, TODAS);
      const actual = () => barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href');

      for (const ruta of ['/area-ventas', '/caja', '/presupuestos', '/clientes', '/reportes']) {
        await irA(ruta);
        expect(actual(), ruta).toBe('/area-ventas');
      }
    });

    it('«Más» abre el cajón del menú y, al cerrarlo, la barra vuelve al estado cerrado', async () => {
      const el = await render(true);
      const boton = mas(el)!;
      expect(boton, 'el destino «Más»').not.toBeNull();
      expect(boton.getAttribute('aria-controls')).toBe('menu-lateral');
      const cajon = el.querySelector<HTMLElement>(`#${boton.getAttribute('aria-controls')}`)!;
      expect(cajon.tagName).toBe('ASIDE');
      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);

      boton.click();
      await estable();

      expect(boton.getAttribute('aria-expanded')).toBe('true');
      expect(cajon.classList.contains('abierto')).toBe(true);

      el.querySelector<HTMLElement>('.velo')!.dispatchEvent(
        new MouseEvent('click', { bubbles: true }),
      );
      await estable();

      expect(boton.getAttribute('aria-expanded')).toBe('false');
      expect(cajon.classList.contains('abierto')).toBe(false);
    });

    it('el Vendedor también tiene «Ventas» en la barra, y apunta al hub: nunca a la consulta que no puede abrir', async () => {
      const el = await render(false);

      expect(destinos(el)).toEqual(['Mostrador', 'Ventas', 'Artículos', 'Más']);
      expect(barra(el)!.querySelector('a[href="/area-ventas"]')).not.toBeNull();
      expect(barra(el)!.querySelector('a[href="/ventas"]')).toBeNull();
      expect(barra(el)!.querySelector('a[href="/articulos"]')).not.toBeNull();
    });

    const VENDEDOR: Sesion = {
      userId: 2, nombre: 'Ana', email: 'ana@example.test', rol: 'VENDEDOR',
      ferreteriaId: 1, ferreteriaNombre: 'La Tuerca',
    };

    it('sigue el orden y los rótulos de los grupos con opciones visibles, y usa el primer ícono visible', async () => {
      tokenMenu.set('sesion-vendedora');
      obtenerMenu.mockReturnValue(of({
        grupos: [
          { id: 'solo-dueno', rotulo: 'Solo Dueño', tono: 'ventas', claves: ['reportes'] },
          { id: 'vacio', rotulo: 'Oculto', tono: 'compras', claves: ['proveedores'] },
          { id: 'diario', rotulo: 'Diario', tono: 'ventas', claves: ['rubros', 'articulos'] },
          { id: 'articulos', rotulo: 'Inventario', tono: 'articulos', claves: ['remitos'] },
          { id: 'ventas', rotulo: 'Operaciones', tono: 'ventas', claves: ['caja'] },
        ],
        ocultas: ['proveedores'],
      }));
      const el = await render(false, VENDEDOR, TODAS);
      const links = () => Array.from(barra(el)!.querySelectorAll('a'));
      const icono = (selector: string) => el.querySelector(`${selector} app-icono-menu svg`)?.innerHTML;

      expect(destinos(el)).toEqual(['Mostrador', 'Diario', 'Inventario', 'Más']);
      expect(links().map((a) => a.getAttribute('href'))).toEqual(['/mostrador', '/area/diario', '/area/articulos']);
      await irA('/area/diario');
      expect(barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe('/area/diario');
      expect(el.getAttribute('data-seccion')).toBe('ventas');
      expect(boton(el, 'Diario').getAttribute('aria-expanded')).toBe('true');
      expect(icono('.destinos a[href="/area/diario"]')).toBe(icono('aside a[href="/rubros"]'));
      await irA('/articulos');
      expect(barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe('/area/diario');
      await irA('/remitos');
      expect(barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe('/area/articulos');
    });

    it('un nombre de grupo largo se recorta con puntos suspensivos y la flecha sigue a la vista', async () => {
      tokenMenu.set('sesion-vendedora');
      const largo = 'Pedidos urgentes del mostrador';
      obtenerMenu.mockReturnValue(of({
        grupos: [{ id: 'g-1', rotulo: largo, tono: 'ventas', claves: ['caja'] }],
        ocultas: [],
      }));
      const el = await render(false, VENDEDOR, TODAS);
      const grupo = boton(el, largo);

      expect(grupo.querySelector('.rotulo-grupo')?.textContent?.trim()).toBe(largo);
      expect(grupo.getAttribute('title')).toBe(largo);
      expect(grupo.querySelector('.flecha')).not.toBeNull();
    });

    it('usa el ícono de Artículos cuando abre el listado directo, aunque Rubros esté primero', async () => {
      tokenMenu.set('sesion-vendedora');
      obtenerMenu.mockReturnValue(of({
        grupos: [{ id: 'articulos', rotulo: 'Inventario', tono: 'articulos', claves: ['rubros', 'articulos'] }],
        ocultas: [],
      }));
      const el = await render(false, VENDEDOR, TODAS);
      await irA('/articulos');

      const enlace = barra(el)!.querySelector('a[href="/articulos"]');
      const icono = (raiz: Element | null) => raiz?.querySelector('app-icono-menu svg')?.innerHTML;
      expect(enlace?.textContent?.trim()).toBe('Inventario');
      expect(icono(enlace)).toBeDefined();
      expect(icono(enlace)).toBe(icono(el.querySelector('aside a[href="/articulos"]')));
    });

    it('distingue el destino fijo Mostrador de un grupo guardado con ese id al marcar la pantalla activa', async () => {
      tokenMenu.set('sesion-vendedora');
      obtenerMenu.mockReturnValue(of({
        grupos: [{ id: 'mostrador', rotulo: 'Diario', tono: 'ventas', claves: ['caja'] }],
        ocultas: [],
      }));
      const el = await render(false, VENDEDOR, TODAS);
      const nav = barra(el)!;
      expect(nav.querySelector('a[href="/area/mostrador"]')?.textContent?.trim()).toBe('Diario');
      const activos = (selector: string) => Array.from(nav.querySelectorAll(selector), (a) => a.getAttribute('href'));

      await irA('/mostrador');
      expect(activos('a[aria-current="page"]')).toEqual(['/mostrador']);
      expect(activos('a.activo')).toEqual(['/mostrador']);

      await irA('/area/mostrador');
      expect(activos('a[aria-current="page"]')).toEqual(['/area/mostrador']);
      expect(activos('a.activo')).toEqual(['/area/mostrador']);
    });

    it('abre el grupo de la pantalla actual cuando llega la disposición y conserva el acceso directo a Artículos', async () => {
      const el = await render(false, VENDEDOR, TODAS);
      await irA('/clientes');
      expect(boton(el, 'Ventas').getAttribute('aria-expanded')).toBe('true');
      obtenerMenu.mockReturnValue(of({
        grupos: [
          { id: 'articulos', rotulo: 'Inventario', tono: 'compras', claves: ['rubros', 'articulos'] },
          { id: 'ventas', rotulo: 'Operaciones', tono: 'ventas', claves: ['caja'] },
          { id: 'diario', rotulo: 'Diario', tono: 'articulos', claves: ['clientes'] },
        ],
        ocultas: [],
      }));
      tokenMenu.set('sesion-vendedora');
      await estable();

      expect(destinos(el)).toEqual(['Mostrador', 'Inventario', 'Operaciones', 'Más']);
      expect(boton(el, 'Diario').getAttribute('aria-expanded')).toBe('true');
      expect(el.getAttribute('data-seccion')).toBe('articulos');
      await irA('/articulos');
      expect(barra(el)!.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe('/articulos');
      const iconoVentas = barra(el)!.querySelector('a[href="/area-ventas"] app-icono-menu svg');
      expect(iconoVentas?.querySelector('path')?.getAttribute('d')).toContain('M3.5 1.5h9v13');
      expect(el.getAttribute('data-seccion')).toBe('compras');
    });

    it('el super admin no ve la barra: no tiene destinos de ferretería que ofrecer', async () => {
      const el = await render(false, SUPER_ADMIN);

      // Un nav con un solo «Más» es ruido: sin destinos reales, la barra no existe y el cajón sigue en el encabezado.
      expect(barra(el)).toBeNull();
      expect(el.querySelector('header .abrir-menu')).not.toBeNull();
    });
  });
});
