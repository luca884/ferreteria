import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { VentaHoja } from './venta-hoja';
import { VentaConsultada } from './ventas.models';
import { ventaDePrueba } from './ventas.testing';

registerLocaleData(localeEsAr);

describe('VentaHoja: renglones (QA B-2 y C)', () => {
  let fixture: ComponentFixture<VentaHoja>;

  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.renglones tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => limpio(td.textContent)),
    );

  async function crear(venta: VentaConsultada) {
    await TestBed.configureTestingModule({
      imports: [VentaHoja],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(VentaHoja);
    fixture.componentRef.setInput('venta', venta);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('con descuento global, «Dto.» lo muestra en el renglón y el total del renglón no lleva el recargo, que va abajo', async () => {
    // 2 × $ 1.500 con 10 % global y $ 270 de recargo de la tarjeta: el renglón queda en $ 2.700 antes del recargo.
    await crear(ventaDePrueba());

    expect(filas()).toEqual([['Martillo', '2', '$ 1.500,00', '10 %', '$ 2.700,00']]);
  });

  it('con descuento del renglón y global, «Dto.» es el efectivo: 5 % y 10 % dan 14,5 %', async () => {
    const base = ventaDePrueba();
    await crear(
      ventaDePrueba({
        subtotal: 3000,
        descuento: 435,
        recargo: 0,
        total: 2565,
        renglones: [{ ...base.renglones[0], descuentoPorcentaje: 5, total: 2565, recargo: 0 }],
      }),
    );

    expect(filas()).toEqual([['Martillo', '2', '$ 1.500,00', '14,5 %', '$ 2.565,00']]);
  });

  it('sin descuentos ni recargo, «Dto.» queda vacío y el total es el del renglón', async () => {
    const base = ventaDePrueba();
    await crear(
      ventaDePrueba({
        descuentoPorcentaje: 0,
        descuento: 0,
        recargo: 0,
        total: 3000,
        pagos: [],
        renglones: [{ ...base.renglones[0], total: 3000, recargo: 0 }],
      }),
    );

    expect(filas()).toEqual([['Martillo', '2', '$ 1.500,00', '', '$ 3.000,00']]);
  });
});
