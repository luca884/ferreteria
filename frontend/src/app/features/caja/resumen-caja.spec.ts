import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
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
    cobranzas: { EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 0,
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
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) } },
      ],
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

  describe('imprimir (G73)', () => {
    it('el resumen es lo imprimible y «Imprimir» llama a window.print', async () => {
      await crear(detalle());
      const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

      expect(el().querySelector('section.caja-imprimible')).not.toBeNull();
      el().querySelector<HTMLButtonElement>('button.imprimir')!.click();

      expect(imprimir).toHaveBeenCalledOnce();
      expect(limpio(el().querySelector('button.imprimir'))).toBe('Imprimir');
    });

    it('el encabezado de la hoja trae la ferretería y cuándo se emitió', async () => {
      await crear(detalle());

      const encabezado = el().querySelector('.encabezado-impreso')!;
      expect(limpio(encabezado.querySelector('.ferreteria'))).toBe('Ferretería El Tornillo');
      expect(limpio(encabezado.querySelector('.emitido'))).toMatch(/^Emitido el \d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/);
    });

    it('imprime lo que se ve: el botón y el encabezado no cambian los renglones del resumen', async () => {
      await crear(detalle());

      expect(limpio(linea('Efectivo esperado')!.querySelector('strong'))).toContain('6.000');
      expect(el().querySelector('#resumen-titulo')!.textContent).toBe('Resumen de la caja');
    });
  });

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

  describe('anulaciones de cobranzas (CC-081, CC-082, CC-100)', () => {
    const SIN: Record<string, number> = { EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 };
    /** El ejemplo del spec: inicial 5.000; cobranza 700 en efectivo anulada: el esperado vuelve a 5.000 + ventas. */
    const conAnulaciones = (cambios: Partial<DetalleCaja> = {}) =>
      detalle({
        ventas: { EFECTIVO: 1000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
        totalVentas: 1000,
        cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
        totalCobranzas: 1000,
        anulaciones: { ...SIN, EFECTIVO: 700 } as DetalleCaja['cobranzas'],
        totalAnulaciones: 700,
        efectivoEsperado: 6000,
        ...cambios,
      });

    it('«Anulaciones de cobranzas en efectivo» muestra −$700,00 y dice que resta del efectivo esperado', async () => {
      await crear(conAnulaciones());

      const fila = linea('Anulaciones de cobranzas en efectivo')!;
      expect(limpio(fila.querySelector('strong'))).toMatch(/^−\s?\$\s700,00$/);
      expect(limpio(fila)).toContain('resta del efectivo esperado');
      expect(fila.classList.contains('informativo')).toBe(false);
    });

    it('«Anulaciones de cobranzas por transferencia» es informativa: no entra en el efectivo esperado', async () => {
      await crear(
        conAnulaciones({ anulaciones: { ...SIN, EFECTIVO: 700, TRANSFERENCIA: 300 } as DetalleCaja['cobranzas'], totalAnulaciones: 1000 }),
      );

      const fila = linea('Anulaciones de cobranzas por transferencia')!;
      expect(limpio(fila.querySelector('strong'))).toMatch(/^−\s?\$\s300,00$/);
      expect(fila.classList.contains('informativo')).toBe(true);
      expect(limpio(fila)).toContain('informativo, no entra en el efectivo esperado');
    });

    it('las filas van después de las cobranzas y antes de los ingresos', async () => {
      await crear(conAnulaciones());

      const nombres = lineas().map((l) => limpio(l.querySelector('span')));
      expect(nombres.slice(7, 12)).toEqual([
        'Cobranzas en efectivo',
        'Cobranzas por transferencia',
        'Anulaciones de cobranzas en efectivo',
        'Anulaciones de cobranzas por transferencia',
        'Ingresos',
      ]);
    });

    it('con todo en 0 el renglón se muestra igual, en 0 (consistente con las cobranzas)', async () => {
      await crear(conAnulaciones({ anulaciones: SIN as DetalleCaja['cobranzas'], totalAnulaciones: 0 }));

      expect(limpio(linea('Anulaciones de cobranzas en efectivo')!.querySelector('strong'))).toMatch(/^\$\s0,00$/);
      expect(limpio(linea('Anulaciones de cobranzas por transferencia')!.querySelector('strong'))).toMatch(/^\$\s0,00$/);
    });

    it('con `anulaciones` indefinido (backend viejo) omite el renglón, sin error, y el resto se ve igual', async () => {
      await crear(conAnulaciones({ anulaciones: undefined, totalAnulaciones: undefined }));

      expect(linea('Anulaciones de cobranzas en efectivo')).toBeUndefined();
      expect(linea('Anulaciones de cobranzas por transferencia')).toBeUndefined();
      expect(limpio(linea('Cobranzas en efectivo')!.querySelector('strong'))).toContain('700');
      expect(limpio(linea('Efectivo esperado')!.querySelector('strong'))).toContain('6.000');
    });

    it('el efectivo esperado se muestra tal cual lo manda el backend (no se vuelve a calcular acá)', async () => {
      await crear(conAnulaciones({ efectivoEsperado: 5300 }));

      expect(limpio(linea('Efectivo esperado')!.querySelector('strong'))).toContain('5.300');
    });

    it('cerrada: el contado y la diferencia salen contra el esperado que ya descuenta la anulación', async () => {
      await crear(conAnulaciones({ estado: 'CERRADA', efectivoContado: 6000, diferencia: 0 }));

      expect(limpio(linea('Anulaciones de cobranzas en efectivo')!.querySelector('strong'))).toMatch(/700,00$/);
      expect(limpio(linea('Efectivo contado')!.querySelector('strong'))).toContain('6.000');
      expect(limpio(linea('Diferencia')!.querySelector('strong'))).toBe('Sin diferencia');
    });
  });

  describe('cobranzas de cuenta corriente (CC-053, CJ-R-013)', () => {
    /** El ejemplo del spec: inicial 5.000; ventas en efectivo 1.000; cobranza 700 en efectivo y 300 por transferencia. */
    const conCobranzas = (cambios: Partial<DetalleCaja> = {}) =>
      detalle({
        ventas: { EFECTIVO: 1000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
        totalVentas: 1000,
        cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
        totalCobranzas: 1000,
        efectivoEsperado: 6700,
        ...cambios,
      });

    it('«Cobranzas en efectivo» muestra lo cobrado y dice que suma al efectivo esperado', async () => {
      await crear(conCobranzas());

      const fila = linea('Cobranzas en efectivo')!;
      expect(limpio(fila.querySelector('strong'))).toContain('700');
      expect(limpio(fila)).toContain('suma al efectivo esperado');
      expect(fila.classList.contains('informativo')).toBe(false);
    });

    it('«Cobranzas por transferencia» es informativa: no entra en el efectivo esperado', async () => {
      await crear(conCobranzas());

      const fila = linea('Cobranzas por transferencia')!;
      expect(limpio(fila.querySelector('strong'))).toContain('300');
      expect(fila.classList.contains('informativo')).toBe(true);
      expect(limpio(fila)).toContain('no entra en el efectivo esperado');
    });

    it('las filas van después del total de ventas y antes de los ingresos', async () => {
      await crear(conCobranzas());

      const nombres = lineas().map((l) => limpio(l.querySelector('span')));
      expect(nombres.slice(6, 10)).toEqual([
        'Total de ventas',
        'Cobranzas en efectivo',
        'Cobranzas por transferencia',
        'Ingresos',
      ]);
    });

    it('el total de ventas no incluye las cobranzas y el efectivo esperado sale como lo mandó el servidor', async () => {
      await crear(conCobranzas());

      expect(limpio(linea('Total de ventas')!.querySelector('strong'))).toContain('1.000');
      expect(limpio(linea('Total de ventas')!.querySelector('strong'))).not.toContain('2.000');
      expect(limpio(linea('Efectivo esperado')!.querySelector('strong'))).toContain('6.700');
    });

    it('sin cobranzas las dos filas se muestran igual, en 0', async () => {
      await crear(detalle());

      expect(limpio(linea('Cobranzas en efectivo')!.querySelector('strong'))).toContain('0');
      expect(limpio(linea('Cobranzas por transferencia')!.querySelector('strong'))).toContain('0');
    });

    it('cerrada: el contado y la diferencia salen contra el esperado con cobranzas (6.700 contra 6.700 = sin diferencia)', async () => {
      await crear(conCobranzas({ estado: 'CERRADA', efectivoContado: 6700, diferencia: 0 }));

      expect(limpio(linea('Efectivo contado')!.querySelector('strong'))).toContain('6.700');
      expect(limpio(linea('Diferencia')!.querySelector('strong'))).toBe('Sin diferencia');
    });
  });
});
