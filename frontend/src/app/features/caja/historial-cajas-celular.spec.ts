import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { CajaCerrada, DetalleCaja } from './caja.models';
import { CajaService } from './caja.service';
import { HistorialCajas } from './historial-cajas';

registerLocaleData(localeEsAr);

/**
 * El historial de cajas y el resumen en el celular (movil-sectores T11a, S1, S5): sólo cambia el CSS (`styles.css`,
 * «Ventas: caja en el celular»), que Vitest no lee. Lo que sí se fija acá es lo que ese CSS necesita del DOM y que no
 * cambia nada de lo de hoy: la tabla con sus ocho columnas, el paginador, el detalle con los movimientos en el orden de
 * las celdas (hora, tipo, motivo, usuario, importe: el CSS del celular arma las tarjetas con `nth-child`) y el mensaje
 * del Vendedor.
 */
describe('HistorialCajas en el celular (T11a)', () => {
  const cerrada = (id: number, diferencia: number): CajaCerrada => ({
    id,
    fechaApertura: '2026-10-15T08:00:00',
    fechaCierre: '2026-10-15T20:00:00',
    abiertaPor: 'Ana',
    cerradaPor: 'Beto',
    efectivoInicial: 1000,
    diferenciaApertura: null,
    efectivoEsperado: 5000,
    efectivoContado: 5000 + diferencia,
    diferencia,
  });
  const detalle: DetalleCaja = {
    id: 8,
    estado: 'CERRADA',
    fechaApertura: '2026-10-15T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 1000,
    efectivoCierreAnterior: 1000,
    diferenciaApertura: 0,
    fechaCierre: '2026-10-15T20:00:00',
    cerradaPor: 'Beto',
    ventas: { EFECTIVO: 4000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalVentas: 4000,
    cobranzas: { EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 0,
    ingresos: 0,
    gastos: 50,
    retiros: 0,
    efectivoEsperado: 5000,
    efectivoContado: 4900,
    diferencia: -100,
    movimientos: [{ id: 1, tipo: 'GASTO', importe: 50, motivo: 'Luz', fecha: '2026-10-15T12:00:00', usuario: 'Ana' }],
  };

  let fixture: ComponentFixture<HistorialCajas>;
  let servicio: Record<'historial' | 'detalle', ReturnType<typeof vi.fn>>;

  async function crear(dueno: boolean) {
    vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('639.98px'), media: c }));
    servicio = {
      historial: vi.fn((page: number) => of({ items: [cerrada(8, -100), cerrada(7, 0)], page, size: 20, totalElements: 40, totalPages: 2 })),
      detalle: vi.fn(() => of(detalle)),
    };
    await TestBed.configureTestingModule({
      imports: [HistorialCajas],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CajaService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HistorialCajas);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  it('Dueño: cada caja es una fila con sus ocho datos, la diferencia en palabras y el botón «Ver detalle»', async () => {
    await crear(true);

    expect(servicio.historial).toHaveBeenCalledWith(0, 20);
    const filas = Array.from(el().querySelectorAll('table.tabla-tarjetas tbody tr'));
    expect(filas).toHaveLength(2);
    expect(Array.from(filas[0].children).map((c) => normal(c.textContent))).toEqual([
      '15/10 08:00',
      '15/10 20:00',
      'Ana',
      'Beto',
      '$ 5.000,00',
      '$ 4.900,00',
      'Faltan $ 100,00',
      'Ver detalle',
    ]);
    expect(filas[0].querySelector('td.diferencia')!.classList.contains('falta')).toBe(true);
    expect(normal(filas[1].querySelector('td.diferencia')?.textContent)).toBe('Sin diferencia');
    expect(filas[0].querySelector<HTMLButtonElement>('button.detalle')!.type).toBe('button');
  });

  it('Dueño: el paginador dice «Página 1 de 2» y «Siguiente» pide la página siguiente', async () => {
    await crear(true);

    const nav = el().querySelector('nav.paginas')!;
    expect(normal(nav.textContent)).toBe('Anterior Página 1 de 2 Siguiente');
    const [anterior, siguiente] = Array.from(nav.querySelectorAll('button'));
    expect(anterior.disabled).toBe(true);
    siguiente.click();
    await refrescar();

    expect(servicio.historial).toHaveBeenLastCalledWith(1, 20);
  });

  it('Dueño: tocar una caja abre su detalle y los movimientos llegan con las celdas en el orden del CSS del celular', async () => {
    await crear(true);

    el().querySelector<HTMLButtonElement>('button.detalle')!.click();
    await refrescar();

    expect(servicio.detalle).toHaveBeenCalledWith(8);
    expect(normal(el().querySelector('app-dialogo-detalle h2')?.textContent)).toBe('Caja del 15/10 08:00');
    const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
    expect(resumen).toContain('Efectivo esperado $ 5.000,00');
    expect(resumen).toContain('Efectivo contado $ 4.900,00');
    expect(resumen).toContain('Diferencia Faltan $ 100,00');
    const celdas = Array.from(el().querySelectorAll('app-resumen-caja tbody tr:first-child > td')).map((c) => normal(c.textContent));
    expect(celdas).toEqual(['15/10 12:00', 'Gasto', 'Luz', 'Ana', '−$ 50,00']);
    expect(el().querySelector('app-resumen-caja li.diferencia')!.classList.contains('falta')).toBe(true);
    expect(el().querySelector('app-resumen-caja tbody td.num.egreso')).not.toBeNull();
  });

  it('Vendedor: ve el mensaje de hoy y no se pide ningún historial', async () => {
    await crear(false);

    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('El historial de cajas es solo del dueño.');
    expect(servicio.historial).not.toHaveBeenCalled();
    expect(el().querySelector('table')).toBeNull();
  });
});
