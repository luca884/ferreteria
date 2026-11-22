import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Title } from '@angular/platform-browser';
import { routes } from '../../app.routes';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Disposicion } from '../../layout/menu';
import { AvisosFacturacion } from '../mostrador/avisos-facturacion.models';
import { AvisosFacturacionService } from '../mostrador/avisos-facturacion.service';
import { HubVentas } from './hub-ventas';

// El hub «Ventas» de la barra del celular (movil-sectores T2, S3): una vista del grupo Ventas del menú, por rol.
describe('HubVentas (movil-sectores T2, S3)', () => {
  let fixture: ComponentFixture<HubVentas>;
  let leer: ReturnType<typeof vi.fn>;

  const sinNada: AvisosFacturacion = { rechazadas: 0, pendientesViejas: 0, enRevision: 0, bloqueo: null };

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLAnchorElement>('a.fila-hub'));
  const rotulos = () => filas().map((fila) => fila.querySelector('b')?.textContent?.trim());
  const destinos = () => filas().map((fila) => fila.getAttribute('href'));
  const pastilla = () => el().querySelector<HTMLElement>('.fila-hub .pastilla-hub');

  async function crear(dueno: boolean, avisos: AvisosFacturacion | 'falla' = sinNada) {
    leer = vi.fn(() =>
      avisos === 'falla' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(avisos),
    );
    await TestBed.configureTestingModule({
      imports: [HubVentas],
      providers: [
        provideRouter([]),
        provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: { esDueno: () => dueno, token: signal(null) } },
        { provide: AvisosFacturacionService, useValue: { leer } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HubVentas);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('el Dueño ve las seis opciones del grupo Ventas, en el orden del menú, y cada fila lleva a su pantalla', async () => {
    await crear(true);

    expect(rotulos()).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes', 'Ventas', 'Reportes']);
    expect(destinos()).toEqual(['/presupuestos', '/remitos', '/caja', '/clientes', '/ventas', '/reportes']);
  });

  it('el Vendedor ve sólo lo que ya puede abrir: ni Ventas ni Reportes', async () => {
    await crear(false);

    expect(rotulos()).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes']);
    expect(destinos()).toEqual(['/presupuestos', '/remitos', '/caja', '/clientes']);
    expect(el().querySelector('a[href="/ventas"]')).toBeNull();
    expect(el().querySelector('a[href="/reportes"]')).toBeNull();
  });

  it('cada fila explica en una línea para qué sirve, y la del Vendedor no promete la cuenta corriente', async () => {
    await crear(true);
    const aclaracion = (ruta: string) =>
      el().querySelector(`a[href="${ruta}"] small`)?.textContent?.trim();
    expect(aclaracion('/clientes')).toBe('Alta, cuenta corriente y cobranza');
    expect(aclaracion('/ventas')).toBe('Consulta, facturas y anulaciones');
    expect(filas().every((fila) => !!fila.querySelector('small')?.textContent?.trim())).toBe(true);

    TestBed.resetTestingModule();
    await crear(false);
    expect(aclaracion('/clientes')).toBe('Buscar, alta y edición');
  });

  it('el título es un h1 «Ventas»: el encabezado del shell lo lee de ahí', async () => {
    await crear(true);

    expect(el().querySelector('h1')?.textContent?.trim()).toBe('Ventas');
  });

  it('la pastilla de «Ventas» suma las facturas para revisar y lo dice a los lectores de pantalla', async () => {
    await crear(true, { rechazadas: 2, pendientesViejas: 3, enRevision: 1, bloqueo: null });

    expect(pastilla()?.textContent?.trim()).toBe('6');
    expect(pastilla()?.getAttribute('aria-label')).toBe('6 facturas para revisar');
    // Sólo la fila de Ventas la lleva.
    expect(el().querySelectorAll('.pastilla-hub').length).toBe(1);
    expect(pastilla()?.closest('a')?.getAttribute('href')).toBe('/ventas');
  });

  it('con una sola factura, el aviso va en singular', async () => {
    await crear(true, { ...sinNada, rechazadas: 1 });

    expect(pastilla()?.getAttribute('aria-label')).toBe('1 factura para revisar');
  });

  it('sin facturas para revisar no hay pastilla', async () => {
    await crear(true, sinNada);

    expect(pastilla()).toBeNull();
  });

  it('si no se pueden leer los avisos, el hub se ve igual y sin pastilla', async () => {
    await crear(true, 'falla');

    expect(rotulos()).toHaveLength(6);
    expect(pastilla()).toBeNull();
  });

  it('al Vendedor no se le piden los avisos: el servidor le respondería 403', async () => {
    await crear(false);

    expect(leer).not.toHaveBeenCalled();
    expect(pastilla()).toBeNull();
  });
});

@Component({ template: '<h1>Mostrador</h1>' })
class PantallaMostrador {}

describe('hub por grupo (CU-17 T3)', () => {
  const disposicion: Disposicion = {
    grupos: [
      { id: 'diario', rotulo: 'Mi día', tono: 'compras', claves: ['ventas', 'caja', 'clientes'] },
      { id: 'inventario', rotulo: 'Inventario', tono: 'articulos', claves: ['articulos'] },
      { id: 'restringido', rotulo: 'Solo Dueño', tono: 'ventas', claves: ['reportes'] },
    ],
    ocultas: ['clientes'],
  };

  async function preparar(dueno: boolean) {
    const rutas = routes.find((r) => r.path === '')!.children!;
    const hubs = rutas.filter((r) => r.path === 'area/:grupo' || r.path === 'area-ventas');
    const leer = vi.fn(() => of({ rechazadas: 1, pendientesViejas: 0, enRevision: 0 }));
    TestBed.configureTestingModule({ providers: [
      provideRouter([...hubs, { path: 'mostrador', component: PantallaMostrador }, { path: '**', redirectTo: 'mostrador' }]),
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: {
        esDueno: signal(dueno), token: signal('sesion'), debeCambiarContrasena: signal(false),
        sesion: signal({ userId: 1, ferreteriaId: 1, rol: dueno ? 'DUENO' : 'VENDEDOR' }),
      } },
      { provide: AvisosFacturacionService, useValue: { leer } },
    ] });
    return { harness: await RouterTestingHarness.create(), leer };
  }

  async function entrar(harness: RouterTestingHarness, url: string) {
    const navegacion = harness.navigateByUrl(url);
    const http = TestBed.inject(HttpTestingController);
    const pedido = await vi.waitFor(() => http.expectOne({ method: 'GET', url: '/api/menu' }), { timeout: 500 });
    pedido.flush({ origen: 'USUARIO', disposicion });
    await navegacion;
    http.verify();
  }

  it.each([true, false])('un enlace directo espera el menú y lista las opciones en orden para esDueño=%s', async (dueno) => {
    const { harness, leer } = await preparar(dueno);
    await entrar(harness, '/area/diario');

    const pantalla = harness.routeNativeElement!;
    expect(pantalla.querySelector('h1')?.textContent?.trim()).toBe('Mi día');
    expect(TestBed.inject(Title).getTitle()).toBe('Mi día · Ferretería');
    const hrefs = () => Array.from(pantalla.querySelectorAll('.fila-hub')).map((a) => a.getAttribute('href'));
    expect(hrefs()).toEqual(dueno ? ['/ventas', '/caja'] : ['/caja']);
    const pill = pantalla.querySelector('.pastilla-hub');
    expect(pill?.closest('a')?.getAttribute('href') ?? null).toBe(dueno ? '/ventas' : null);
    expect(leer).toHaveBeenCalledTimes(dueno ? 1 : 0);

    await harness.navigateByUrl('/area/inventario');
    expect(pantalla.querySelector('h1')?.textContent?.trim()).toBe('Inventario');
    expect(hrefs()).toEqual(['/articulos']);
    expect(pantalla.querySelector('.pastilla-hub')).toBeNull();
    expect(leer).toHaveBeenCalledTimes(dueno ? 1 : 0);
  });

  it.each(['/area/no-existe', '/area/restringido', '/area-ventas'])('al Vendedor sin opciones en %s lo lleva a Mostrador', async (url) => {
    const { harness, leer } = await preparar(false);
    await entrar(harness, url);

    expect(TestBed.inject(Router).url).toBe('/mostrador');
    expect(harness.routeNativeElement?.textContent?.trim()).toBe('Mostrador');
    expect(leer).not.toHaveBeenCalled();
  });
});
