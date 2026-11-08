import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { VentaHoja } from './venta-hoja';
import { VentaConsultada } from './ventas.models';
import { ventaDePrueba } from './ventas.testing';
import { documentoB, fiscalDe } from '../../shared/factura/factura.testing';

registerLocaleData(localeEsAr);

describe('VentaHoja: renglones (QA B-2 y C)', () => {
  let fixture: ComponentFixture<VentaHoja>;

  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.renglones tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => limpio(td.textContent)),
    );

  async function crear(venta: VentaConsultada, conLinks = false) {
    await TestBed.configureTestingModule({
      imports: [VentaHoja],
      providers: [provideRouter([]), { provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(VentaHoja);
    fixture.componentRef.setInput('venta', venta);
    fixture.componentRef.setInput('conLinks', conLinks);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('con descuento global, «Dto.» lo muestra en el renglón y el total del renglón no lleva el recargo, que va abajo', async () => {
    // 2 × $ 1.500 con 10 % global y $ 270 de recargo de la tarjeta: el renglón queda en $ 2.700 antes del recargo.
    await crear(ventaDePrueba());

    expect(filas()).toEqual([['Martillo', '2 unidades', '$ 1.500,00', '10 %', '$ 2.700,00']]);
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

    expect(filas()).toEqual([['Martillo', '2 unidades', '$ 1.500,00', '14,5 %', '$ 2.565,00']]);
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

    expect(filas()).toEqual([['Martillo', '2 unidades', '$ 1.500,00', '', '$ 3.000,00']]);
  });

  describe('informacion-y-links (S2, S3, S4)', () => {
    const el = () => fixture.nativeElement as HTMLElement;

    it('la cantidad sale con su unidad: «25,5 metros», «1 unidad»', async () => {
      const base = ventaDePrueba();
      await crear({
        ...base,
        renglones: [
          { ...base.renglones[0], descripcion: 'Cable', unidad: 'METRO', cantidad: 25.5 },
          { ...base.renglones[0], renglon: 2, descripcion: 'Martillo', unidad: 'UNIDAD', cantidad: 1 },
        ],
      });

      expect(filas().map((f) => f[1])).toEqual(['25,5 metros', '1 unidad']);
    });

    it('el precio dice por qué unidad es: «/ m»; por pieza y el total del renglón no llevan (#36)', async () => {
      const base = ventaDePrueba();
      await crear({
        ...base,
        renglones: [
          { ...base.renglones[0], descripcion: 'Cable', unidad: 'METRO' },
          { ...base.renglones[0], renglon: 2, descripcion: 'Martillo', unidad: 'UNIDAD' },
        ],
      });

      expect(filas().map((f) => [f[2], f[4]])).toEqual([
        ['$ 1.500,00 / m', '$ 2.700,00'],
        ['$ 1.500,00', '$ 2.700,00'],
      ]);
    });

    it('dice de qué remito o presupuesto salió, con su número y un link a su pop-up', async () => {
      await crear(ventaDePrueba({ remitoId: 4, remitoNumero: 2 }), true);

      const link = el().querySelector<HTMLAnchorElement>('.notas a')!;
      expect(limpio(el().querySelector('.notas')!.textContent)).toBe('Salió del remito N.º 2');
      expect(link.getAttribute('href')).toBe('/remitos/4');

      TestBed.resetTestingModule();
      await crear(ventaDePrueba({ presupuestoId: 9, presupuestoNumero: 5 }), true);

      expect(limpio(el().querySelector('.notas')!.textContent)).toBe('Salió del presupuesto N.º 5');
      expect(el().querySelector('.notas a')!.getAttribute('href')).toBe('/presupuestos/9');
    });

    it('en la hoja para imprimir (sin links) el origen es texto; sin el número, dice «Salió de un remito»', async () => {
      await crear(ventaDePrueba({ remitoId: 4, remitoNumero: 2 }));

      expect(limpio(el().querySelector('.notas')!.textContent)).toBe('Salió del remito N.º 2');
      expect(el().querySelector('.notas a')).toBeNull();

      TestBed.resetTestingModule();
      await crear(ventaDePrueba({ remitoId: 4 }), true);

      expect(limpio(el().querySelector('.notas')!.textContent)).toBe('Salió de un remito');
      expect(el().querySelector('.notas a')).toBeNull();
    });

    it('los renglones de la nota de crédito y de la anulación se titulan como el comprobante', async () => {
      const caption = () => limpio(el().querySelector('.renglones caption')!.textContent);
      await crear(ventaDePrueba());
      expect(caption()).toBe('Renglones de la venta');

      TestBed.resetTestingModule();
      await crear(ventaDePrueba({ tipo: 'NOTA_CREDITO', fiscal: fiscalDe(documentoB()) }));
      expect(caption()).toBe('Renglones de la nota de crédito');

      TestBed.resetTestingModule();
      await crear(ventaDePrueba({ tipo: 'NOTA_CREDITO' }));
      expect(caption()).toBe('Renglones de la anulación');
    });
  });
});
