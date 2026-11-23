import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Sesion } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { DISPOSICION_ORIGINAL, Disposicion } from './menu';
import { MenuActual } from './menu-actual';

// CU-17 T1 (S5, S6): con la disposición original, el menú que sale de `MenuActual` es el de siempre para cada rol.
describe('MenuActual (CU-17 T1)', () => {
  function menuDe(dueno: boolean): MenuActual {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { esDueno: () => dueno, token: signal(null), sesion: signal(null) } },
    ] });
    return TestBed.inject(MenuActual);
  }

  it('el Vendedor ve sus grupos y el Dueño los cinco', () => {
    expect(menuDe(false).grupos().map((g) => g.id)).toEqual(['ventas', 'articulos', 'compras']);
    TestBed.resetTestingModule();
    expect(menuDe(true).grupos().map((g) => g.id)).toEqual([
      'ventas',
      'articulos',
      'compras',
      'precios',
      'configuracion',
    ]);
  });

  it('ubica una pantalla por su grupo y su tono aunque el rol no pueda abrirla', () => {
    const menu = menuDe(false);
    expect(menu.ubicar('/configuracion/precios')).toEqual({ id: 'precios', tono: 'precios' });
    expect(menu.ubicar('/usuarios')).toEqual({ id: 'configuracion', tono: 'configuracion' });
    expect(menu.ubicar('/mostrador')).toEqual({ id: 'mostrador', tono: 'mostrador' });
    expect(menu.ubicar('/area-ventas')).toEqual({ id: 'ventas', tono: 'ventas' });
    expect(menu.ubicar('/mi-contrasena')).toBeNull();
  });
});

describe('MenuActual (CU-17 T3)', () => {
  const vendedor: Sesion = {
    userId: 7, rol: 'VENDEDOR', ferreteriaId: 1, ferreteriaNombre: 'La Tuerca',
    nombre: 'Ana', email: 'ana@example.test',
  };
  const propia: Disposicion = {
    grupos: [{ id: 'diario', tono: 'compras', rotulo: 'Diario', claves: ['caja', 'reportes'] }],
    ocultas: ['presupuestos'],
  };
  const url = '/api/menu';
  const iniciar = (inicial: Sesion | null = vendedor) => {
    const sesion = signal(inicial);
    const token = signal(inicial ? 'sesion-1' : null);
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: {
        sesion, token, esDueno: computed(() => sesion()?.rol === 'DUENO'),
        debeCambiarContrasena: computed(() => sesion()?.debeCambiarContrasena === true),
      } },
    ] });
    const menu = TestBed.inject(MenuActual);
    const http = TestBed.inject(HttpTestingController);
    TestBed.tick();
    return { menu, http, sesion, token };
  };

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lee el menú propio y lo arma para el rol, sin volver a pedirlo por un cambio de nombre', async () => {
    const { menu, http, sesion } = iniciar();
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
    http.expectOne(url).flush({ origen: 'USUARIO', disposicion: propia });
    await TestBed.inject(ApplicationRef).whenStable();

    expect(menu.disposicion()).toEqual(propia);
    expect(menu.grupos()[0]).toMatchObject({ id: 'diario', opciones: [{ clave: 'caja' }] });
    expect(menu.ubicar('/area/diario')).toEqual({ id: 'diario', tono: 'compras' });
    expect(menu.ubicar('/area-ventas')).toBeNull();
    sesion.set({ ...vendedor, nombre: 'Ana María' });
    TestBed.tick();
    http.expectNone(url);
  });

  it('vuelve a leer tras guardar y cae al original si falla', async () => {
    const { menu, http } = iniciar();
    http.expectOne(url).flush({ origen: 'ROL', disposicion: propia });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(menu.disposicion()).toEqual(propia);

    menu.recargar();
    TestBed.tick();
    http.expectOne(url).flush(null, { status: 500, statusText: 'Internal Server Error' });
    await TestBed.inject(ApplicationRef).whenStable();

    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
  });

  it('si pasan 5001 ms sin respuesta, cancela la lectura y queda el menú original', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    try {
      const { menu, http } = iniciar();
      const pendiente = http.expectOne(url);

      vi.advanceTimersByTime(4_999);
      expect(pendiente.cancelled).toBe(false);
      vi.advanceTimersByTime(2);

      expect(pendiente.cancelled).toBe(true);
      expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
    } finally {
      vi.useRealTimers();
    }
  });

  it('si se recarga antes de la primera respuesta, cancela la lectura y solo aplica la nueva', async () => {
    const { menu, http } = iniciar();
    const primera = http.expectOne(url);
    expect(primera.cancelled).toBe(false);

    menu.recargar();
    TestBed.tick();

    expect(primera.cancelled).toBe(true);
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
    http.expectOne(url).flush({ origen: 'USUARIO', disposicion: propia });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(menu.disposicion()).toEqual(propia);
  });

  it('si la primera lectura devuelve 403, conserva el menú original', async () => {
    const { menu, http } = iniciar();
    http.expectOne(url).flush(null, { status: 403, statusText: 'Forbidden' });
    await TestBed.inject(ApplicationRef).whenStable();

    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
  });

  it('no lee para el super admin ni en ausencia de sesión', () => {
    const { sesion, token, http, menu } = iniciar(null);
    http.expectNone(url);
    sesion.set({ ...vendedor, rol: 'SUPER_ADMIN', ferreteriaId: null, ferreteriaNombre: null });
    token.set('sesion-admin');
    TestBed.tick();
    http.expectNone(url);
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
  });

  it('espera a que cambie la contraseña para leer el menú del dueño', async () => {
    const pendiente: Sesion = { ...vendedor, rol: 'DUENO', debeCambiarContrasena: true };
    const { menu, http, sesion } = iniciar(pendiente);
    http.expectNone(url);
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);

    sesion.set({ ...pendiente, debeCambiarContrasena: false });
    TestBed.tick();
    http.expectOne(url).flush({ origen: 'ORIGINAL', disposicion: null });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
  });

  it('no muestra la disposición anterior al cambiar de usuario o salir y cancela la lectura pendiente', async () => {
    const { menu, http, sesion, token } = iniciar();
    http.expectOne(url).flush({ origen: 'USUARIO', disposicion: propia });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(menu.disposicion()).toEqual(propia);

    sesion.set({ ...vendedor, userId: 8 });
    token.set('sesion-2');
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
    TestBed.tick();
    const pendiente = http.expectOne(url);

    sesion.set(null);
    token.set(null);
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
    TestBed.tick();
    expect(pendiente.cancelled).toBe(true);
    http.expectNone(url);

    sesion.set(vendedor);
    token.set('sesion-3');
    TestBed.tick();
    http.expectOne(url).flush({ origen: 'ORIGINAL', disposicion: null });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(menu.disposicion()).toBe(DISPOSICION_ORIGINAL);
  });
});
