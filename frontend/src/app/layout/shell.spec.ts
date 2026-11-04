import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, WritableSignal, signal } from '@angular/core';
import { Router, Routes, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Sesion } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../core/cotizacion/cotizacion.service';
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
            debeCambiarContrasena: marca,
            logout: () => undefined,
          },
        },
        { provide: CotizacionService, useValue: { obtener, refrescos$: refrescos } },
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

  beforeEach(() => {
    obtener = vi.fn(() => of(COTIZACION));
    refrescos = new Subject<void>();
    marca = signal(false);
  });

  const enlaces = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav a')).map((a) => a.textContent?.trim());

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
      };
      for (const [rotulo, ruta] of Object.entries(rutas)) {
        expect(el.querySelector(`nav a[href="${ruta}"]`)?.textContent?.trim(), ruta).toBe(rotulo);
      }
      expect(el.querySelectorAll('nav a').length).toBe(23);
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

    it('hallazgo 5: con la contraseña por cambiar no la pide (el servidor da 403) y la pide en cuanto se cambia', async () => {
      vi.useFakeTimers();
      marca.set(true);
      const el = await mostrar(await render(false));
      expect(obtener).not.toHaveBeenCalled();
      expect(texto(el.querySelector('app-cotizacion-lateral'))).toContain('Sin datos de cotización por ahora.');

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
});
