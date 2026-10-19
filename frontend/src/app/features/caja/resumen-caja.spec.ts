import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DetalleCaja } from './caja.models';
import { ResumenCaja } from './resumen-caja';

registerLocaleData(localeEsAr);

describe('ResumenCaja (CC-025)', () => {
  /** La caja del escenario del spec: inicial 5.000; 1.000 en efectivo + 2.000 a cuenta corriente; contado 6.000. */
  const detalle = (cambios: Partial<DetalleCaja> = {}): DetalleCaja => ({
    id: 4,
    estado: 'ABIERTA',
    fechaApertura: '2026-10-16T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 5000,
    efectivoCierreAnterior: null,
    diferenciaApertura: null,
    fechaCierre: null,
    cerradaPor: null,
    ventas: { EFECTIVO: 1000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 2000 },
    totalVentas: 3000,
    ingresos: 0,
    gastos: 0,
    retiros: 0,
    efectivoEsperado: 6000,
    efectivoContado: null,
    diferencia: null,
    movimientos: [],
    ...cambios,
  });

  let fixture: ComponentFixture<ResumenCaja>;

  async function crear(d: DetalleCaja) {
    await TestBed.configureTestingModule({
      imports: [ResumenCaja],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(ResumenCaja);
    fixture.componentRef.setInput('detalle', d);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const lineas = () => Array.from(el().querySelectorAll<HTMLElement>('.lineas li'));
  const limpio = (e: Element | undefined | null) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const linea = (nombre: string) => lineas().find((l) => limpio(l.querySelector('span')) === nombre);

  it('muestra «Ventas en cuenta corriente» con lo vendido a cuenta, junto a los otros medios', async () => {
    await crear(detalle());

    const nombres = lineas().map((l) => limpio(l.querySelector('span')));
    expect(nombres.slice(1, 6)).toEqual([
      'Ventas en efectivo',
      'Ventas en débito',
      'Ventas en crédito',
      'Ventas en transferencia',
      'Ventas en cuenta corriente',
    ]);
    expect(limpio(linea('Ventas en cuenta corriente')!.querySelector('strong'))).toContain('2.000');
  });

  it('el renglón de cuenta corriente es informativo: dice que no entra en el efectivo esperado', async () => {
    await crear(detalle());

    const fila = linea('Ventas en cuenta corriente')!;
    expect(fila.classList.contains('informativo')).toBe(true);
    expect(limpio(fila)).toContain('no entra en el efectivo esperado');
  });

  it('los otros medios no llevan la nota informativa', async () => {
    await crear(detalle());

    for (const nombre of ['Ventas en efectivo', 'Ventas en débito', 'Ventas en crédito', 'Ventas en transferencia']) {
      const fila = linea(nombre)!;
      expect(fila.classList.contains('informativo')).toBe(false);
      expect(limpio(fila)).not.toContain('no entra');
    }
  });

  it('con 0 vendido a cuenta corriente se muestra igual, como los otros medios', async () => {
    await crear(detalle({ ventas: { EFECTIVO: 1000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 } }));

    expect(limpio(linea('Ventas en cuenta corriente')!.querySelector('strong'))).toContain('0');
  });

  it('el total de ventas incluye la cuenta corriente y el efectivo esperado sale como lo mandó el servidor', async () => {
    await crear(detalle());

    expect(limpio(linea('Total de ventas')!.querySelector('strong'))).toContain('3.000');
    expect(limpio(linea('Efectivo esperado')!.querySelector('strong'))).toContain('6.000');
  });

  it('cerrada: muestra contado y diferencia sin cambios por la cuenta corriente', async () => {
    await crear(detalle({ estado: 'CERRADA', efectivoContado: 6000, diferencia: 0 }));

    expect(limpio(linea('Efectivo contado')!.querySelector('strong'))).toContain('6.000');
    expect(limpio(linea('Diferencia')!.querySelector('strong'))).toBe('Sin diferencia');
  });
});
