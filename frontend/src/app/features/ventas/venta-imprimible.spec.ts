import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { LEYENDA_VENTA_INTERNA, VentaImprimible } from './venta-imprimible';
import { VentaConsultada } from './ventas.models';
import { ventaDePrueba } from './ventas.testing';

registerLocaleData(localeEsAr);

describe('VentaImprimible', () => {
  let fixture: ComponentFixture<VentaImprimible>;
  let cerradas: number;
  let imprimir: ReturnType<typeof vi.spyOn>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;

  function tecla(key: string, destino: Element = el().querySelector('[role="dialog"]')!): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function abrir(v: VentaConsultada = ventaDePrueba()) {
    imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [VentaImprimible],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre: 'Tornillo SA' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(VentaImprimible);
    cerradas = 0;
    fixture.componentRef.setInput('venta', v);
    fixture.componentInstance.cerrar.subscribe(() => cerradas++);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => imprimir?.mockRestore());

  it('es un diálogo modal titulado con el número de la venta', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(texto('#venta-titulo')).toBe('Venta N.º 7');
    expect(dialogo.getAttribute('aria-labelledby')).toBe('venta-titulo');
  });

  it('lo que sale en la hoja está dentro del diálogo y los botones quedan afuera (contrato con @media print de styles.css)', async () => {
    await abrir();

    const articulo = el().querySelector('article.venta-imprimible')!;
    expect(articulo.closest('.dialogo-fondo > [role="dialog"]')).not.toBeNull();
    const botones = Array.from(el().querySelectorAll('button'));
    expect(botones.map((b) => limpio(b.textContent))).toEqual(['Cerrar', 'Imprimir']);
    expect(botones.every((b) => !articulo.contains(b))).toBe(true);
  });

  it('muestra la ferretería, la fecha, el cliente, quién cobró, los renglones, los totales, los pagos y la leyenda', async () => {
    await abrir();

    const hoja = texto('.venta-imprimible')!;
    expect(texto('.ferreteria')).toBe('Tornillo SA');
    expect(hoja).toContain('22/10/2026 16:05');
    expect(hoja).toContain('Construcciones Pérez');
    expect(hoja).toContain('Ana Vendedora');
    expect(hoja).toContain('Martillo');
    expect(hoja).toContain('Subtotal $ 3.000,00');
    expect(hoja).toContain('Descuento (10 %) −$ 300,00');
    expect(hoja).toContain('Recargo $ 270,00');
    expect(texto('.total')).toContain('$ 2.970,00');
    expect(hoja).toContain('Crédito 1 pago $ 2.970,00');
    expect(texto('.leyenda')).toBe(LEYENDA_VENTA_INTERNA);
    expect(LEYENDA_VENTA_INTERNA).toBe('Comprobante interno, no válido como factura');
  });

  it('con descuento de la forma (recargo negativo): subtotal y «Descuento por forma de pago», sin «Recargo»', async () => {
    await abrir(ventaDePrueba({ descuento: 0, descuentoPorcentaje: 0, subtotal: 3000, recargo: -300, total: 2700 }));

    const hoja = texto('.venta-imprimible')!;
    expect(hoja).toContain('Subtotal $ 3.000,00');
    expect(hoja).toContain('Descuento por forma de pago −$ 300,00');
    expect(hoja).not.toContain('Recargo');
    expect(texto('.total')).toContain('$ 2.700,00');
  });

  it('sin cliente dice «Consumidor Final» y sin descuento ni recargo no muestra esas líneas', async () => {
    await abrir(ventaDePrueba({ clienteNombre: undefined, descuento: 0, descuentoPorcentaje: 0, recargo: 0 }));

    const hoja = texto('.venta-imprimible')!;
    expect(hoja).toContain('Consumidor Final');
    expect(hoja).not.toContain('Descuento');
    expect(hoja).not.toContain('Recargo');
    expect(hoja).not.toContain('Subtotal');
  });

  it('una venta No facturable lo dice', async () => {
    await abrir(ventaDePrueba({ facturable: false }));

    expect(texto('.venta-imprimible')).toContain('No facturable');
  });

  it('Imprimir y Enter imprimen; Cerrar y Esc cierran', async () => {
    await abrir();

    boton('Imprimir').click();
    expect(imprimir).toHaveBeenCalledTimes(1);
    expect(tecla('Enter').defaultPrevented).toBe(true);
    expect(imprimir).toHaveBeenCalledTimes(2);
    boton('Cerrar').click();
    expect(cerradas).toBe(1);
    expect(tecla('Escape').defaultPrevented).toBe(true);
    expect(cerradas).toBe(2);
  });

  it('arranca con el foco en Imprimir', async () => {
    await abrir();

    expect(document.activeElement).toBe(boton('Imprimir'));
  });
});
