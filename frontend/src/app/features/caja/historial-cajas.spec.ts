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

describe('HistorialCajas (CU-10)', () => {
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
    cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 1000,
    ingresos: 0,
    gastos: 0,
    retiros: 0,
    efectivoEsperado: 5000,
    efectivoContado: 5100,
    diferencia: 100,
    movimientos: [{ id: 1, tipo: 'GASTO', importe: 50, motivo: 'Luz', fecha: '2026-10-15T12:00:00', usuario: 'Ana' }],
  };

  let fixture: ComponentFixture<HistorialCajas>;
  let servicio: Record<'historial' | 'detalle', ReturnType<typeof vi.fn>>;

  async function crear(dueno: boolean, totalPages = 1) {
    servicio = {
      historial: vi.fn(() =>
        of({ items: [cerrada(8, 100), cerrada(7, -20)], page: 0, size: 20, totalElements: 2, totalPages }),
      ),
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

  it('el dueño ve las cajas cerradas con su diferencia', async () => {
    await crear(true);

    expect(servicio.historial).toHaveBeenCalledWith(0, 20);
    const filas = Array.from(el().querySelectorAll('tbody tr'));
    expect(filas).toHaveLength(2);
    expect(normal(filas[0].textContent)).toContain('Sobran $ 100,00');
    expect(normal(filas[1].textContent)).toContain('Faltan $ 20,00');
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear(true, 2);

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.cajas')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('al elegir una caja muestra su detalle con los movimientos y quién la abrió y cerró', async () => {
    await crear(true);

    (el().querySelector('tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(servicio.detalle).toHaveBeenCalledWith(8);
    const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
    expect(resumen).toContain('Luz');
    expect(resumen).toContain('Abrió Ana');
    expect(resumen).toContain('Cerró Beto');
  });

  it('el detalle de una caja cerrada muestra sus cobranzas por medio (CC-053)', async () => {
    await crear(true);

    (el().querySelector('tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
    expect(resumen).toContain('Cobranzas en efectivo $ 700,00');
    expect(resumen).toContain('Cobranzas por transferencia $ 300,00');
  });

  it('el vendedor ve un aviso y no se pide el historial', async () => {
    await crear(false);

    expect(servicio.historial).not.toHaveBeenCalled();
    expect(normal(el().textContent)).toContain('El historial de cajas es solo del dueño.');
  });
});
