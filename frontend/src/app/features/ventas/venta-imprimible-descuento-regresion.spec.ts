import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { VentaImprimible } from './venta-imprimible';
import { VentaConsultada } from './ventas.models';
import { ventaDePrueba } from './ventas.testing';

registerLocaleData(localeEsAr);

/** Regresiones del verificador de descuento en efectivo: la venta impresa con el descuento de la forma. */
describe('VentaImprimible con descuento de la forma (regresiones del verificador)', () => {
  let fixture: ComponentFixture<VentaImprimible>;
  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const lineas = () => Array.from(el().querySelectorAll('.totales p')).map((p) => limpio(p.textContent));

  async function abrir(v: VentaConsultada) {
    vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [VentaImprimible],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre: 'Tornillo SA' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(VentaImprimible);
    fixture.componentRef.setInput('venta', v);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('descuento del Vendedor y de la forma juntos: Subtotal una sola vez, las dos lineas y el total', async () => {
    await abrir(ventaDePrueba({ subtotal: 300, descuento: 30, descuentoPorcentaje: 10, recargo: -27, total: 243 }));
    expect(lineas()).toEqual([
      'Subtotal $ 300,00',
      'Descuento (10 %) -$ 30,00',
      'Descuento por forma de pago -$ 27,00',
      'Total $ 243,00',
    ]);
  });

  it('recargo positivo sigue igual que antes (sin Subtotal si no hay descuento)', async () => {
    await abrir(ventaDePrueba({ subtotal: 2700, descuento: 0, descuentoPorcentaje: 0, recargo: 270, total: 2970 }));
    expect(lineas()).toEqual(['Recargo $ 270,00', 'Total $ 2.970,00']);
  });
});
