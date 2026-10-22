import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
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

  let refrescos: Subject<void>;

  beforeEach(() => {
    obtener = vi.fn(() => of(COTIZACION));
    refrescos = new Subject<void>();
  });

  const enlaces = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav a')).map((a) => a.textContent?.trim());

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

        expect(enlaces(el)).not.toContain('Ferreterías');
        expect(el.querySelector('a[href="/admin/ferreterias"]')).toBeNull();
      }
    });
  });

  it('las secciones van en el menú lateral', async () => {
    const el = await render(true);

    const nav = el.querySelector('aside nav[aria-label="Secciones"]');
    expect(nav).not.toBeNull();
    expect(nav!.querySelectorAll('a').length).toBe(14);
  });

  it('«Presupuestos» va después de «Mostrador» y lleva a /presupuestos, para el Vendedor y para el Dueño (GDS-13)', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      expect(enlaces(el).slice(0, 2)).toEqual(['Mostrador', 'Presupuestos']);
      expect(el.querySelector('nav a[href="/presupuestos"]')).not.toBeNull();
    }
  });

  it('«Remitos» va después de «Presupuestos» y lleva a /remitos, para el Vendedor y para el Dueño (G55)', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      expect(enlaces(el).slice(1, 3)).toEqual(['Presupuestos', 'Remitos']);
      expect(el.querySelector('nav a[href="/remitos"]')).not.toBeNull();
    }
  });

  it('«Caja» va después de «Clientes» y lleva a /caja, para el Vendedor y para el Dueño (CU-10)', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      expect(enlaces(el).slice(2, 5)).toEqual(['Remitos', 'Clientes', 'Caja']);
      expect(el.querySelector('nav a[href="/caja"]')).not.toBeNull();
    }
  });

  it('el Dueño ve Mostrador, Artículos, Rubros, Proveedores, Compras y, bajo «Precios», Importar lista, Actualizar precios y Cotización, y bajo «Cobro», Formas de pago, y bajo «Equipo», Usuarios', async () => {
    const el = await render(true);

    expect(enlaces(el)).toEqual([
      'Mostrador',
      'Presupuestos',
      'Remitos',
      'Clientes',
      'Caja',
      'Artículos',
      'Rubros',
      'Proveedores',
      'Compras',
      'Importar lista',
      'Actualizar precios',
      'Cotización',
      'Formas de pago',
      'Usuarios',
    ]);
    expect(Array.from(el.querySelectorAll('nav .grupo')).map((g) => g.textContent?.trim())).toEqual([
      'Precios',
      'Cobro',
      'Equipo',
    ]);
    expect(el.querySelector('a[href="/importaciones"]')).not.toBeNull();
  });

  it('el Dueño ve "Actualizar precios", que lleva a /actualizacion-masiva, después de "Importar lista"', async () => {
    const el = await render(true);

    const nombres = enlaces(el);
    expect(nombres.indexOf('Actualizar precios')).toBe(nombres.indexOf('Importar lista') + 1);
    const enlace = el.querySelector('nav a[href="/actualizacion-masiva"]');
    expect(enlace).not.toBeNull();
    expect(enlace!.textContent?.trim()).toBe('Actualizar precios');
  });

  it('el Dueño ve «Cotización» al final del grupo «Precios» (justo antes de «Formas de pago») y lleva a /configuracion/cotizacion', async () => {
    const el = await render(true);

    const nombres = enlaces(el);
    expect(nombres.indexOf('Cotización')).toBe(nombres.indexOf('Formas de pago') - 1);
    const enlace = el.querySelector('nav a[href="/configuracion/cotizacion"]');
    expect(enlace).not.toBeNull();
    expect(enlace!.textContent?.trim()).toBe('Cotización');
  });

  it('el Vendedor y el Dueño ven «Clientes», entre «Remitos» y «Caja», y lleva a /clientes', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      const nombres = enlaces(el);
      expect(nombres.indexOf('Clientes')).toBe(nombres.indexOf('Remitos') + 1);
      expect(nombres.indexOf('Clientes')).toBe(nombres.indexOf('Caja') - 1);
      expect(el.querySelector('nav a[href="/clientes"]')!.textContent?.trim()).toBe('Clientes');
    }
  });

  it('el Dueño ve «Formas de pago» bajo «Cobro» y lleva a /configuracion/formas-pago; el Vendedor no la ve', async () => {
    const el = await render(true);

    const nombres = enlaces(el);
    expect(nombres.indexOf('Formas de pago')).toBe(nombres.indexOf('Usuarios') - 1);
    expect(el.querySelector('nav a[href="/configuracion/formas-pago"]')).not.toBeNull();

    TestBed.resetTestingModule();
    const vendedor = await render(false);
    expect(vendedor.querySelector('nav a[href="/configuracion/formas-pago"]')).toBeNull();
    expect(enlaces(vendedor)).not.toContain('Formas de pago');
  });

  it('el Dueño ve «Usuarios» bajo «Equipo», al final del menú, y lleva a /usuarios', async () => {
    const el = await render(true);

    expect(enlaces(el).at(-1)).toBe('Usuarios');
    expect(el.querySelector('nav a[href="/usuarios"]')!.textContent?.trim()).toBe('Usuarios');
  });

  it('el Vendedor no ve «Usuarios» ni el grupo «Equipo»', async () => {
    const el = await render(false);

    expect(enlaces(el)).not.toContain('Usuarios');
    expect(el.querySelector('nav a[href="/usuarios"]')).toBeNull();
    expect(Array.from(el.querySelectorAll('nav .grupo')).map((g) => g.textContent?.trim())).not.toContain('Equipo');
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

  it('el Vendedor no ve «Cotización» en el menú (sí ve el bloque de cotización)', async () => {
    const el = await render(false);

    expect(el.querySelector('nav a[href="/configuracion/cotizacion"]')).toBeNull();
    expect(enlaces(el)).not.toContain('Cotización');
    expect(el.querySelector('app-cotizacion-lateral')).not.toBeNull();
  });

  it('el Vendedor no ve "Importar lista", "Actualizar precios" ni el grupo «Precios»', async () => {
    const el = await render(false);

    expect(enlaces(el)).toEqual([
      'Mostrador',
      'Presupuestos',
      'Remitos',
      'Clientes',
      'Caja',
      'Artículos',
      'Rubros',
      'Proveedores',
    ]);
    expect(el.querySelector('a[href="/actualizacion-masiva"]')).toBeNull();
    expect(el.querySelector('a[href="/compras"]')).toBeNull();
    expect(el.querySelector('nav .grupo')).toBeNull();
  });

  it('la sección abierta se marca con aria-current="page"', async () => {
    const el = await render(true, null, [{ path: 'rubros', component: Vacia }]);

    await TestBed.inject(Router).navigateByUrl('/rubros');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('nav a[href="/rubros"]')!.getAttribute('aria-current')).toBe('page');
    expect(el.querySelector('nav a[href="/mostrador"]')!.hasAttribute('aria-current')).toBe(false);
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

  it('"Mostrador" es el primer enlace y lleva a /mostrador, para el Vendedor y para el Dueño', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      const primero = el.querySelector('nav a');
      expect(primero?.textContent?.trim()).toBe('Mostrador');
      expect(primero?.getAttribute('href')).toBe('/mostrador');
    }
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
