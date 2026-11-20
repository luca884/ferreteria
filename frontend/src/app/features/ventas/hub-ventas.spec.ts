import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
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
        { provide: AuthService, useValue: { esDueno: () => dueno } },
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
