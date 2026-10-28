import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { FacturaImprimible } from './factura-imprimible';
import { VentaFacturada } from './factura.models';
import { MARCA_SIMULADO, documentoX, fiscalDe, ventaFacturada, ventaParaFactura } from './factura.testing';

registerLocaleData(localeEsAr);

describe('FacturaImprimible (CU-07 paso 5, S1, S4, S6)', () => {
  let fixture: ComponentFixture<FacturaImprimible>;
  let cerradas: number;
  let descartados: number;
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

  async function abrir(venta: VentaFacturada = ventaFacturada(), entradas: Record<string, unknown> = {}) {
    imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [FacturaImprimible],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(FacturaImprimible);
    cerradas = 0;
    descartados = 0;
    fixture.componentRef.setInput('venta', venta);
    for (const [nombre, valor] of Object.entries(entradas)) fixture.componentRef.setInput(nombre, valor);
    fixture.componentInstance.cerrar.subscribe(() => cerradas++);
    fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => imprimir?.mockRestore());

  it('es un diálogo modal titulado con la letra; la hoja va en el artículo y los botones afuera (contrato con @media print de styles.css)', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.getAttribute('aria-labelledby')).toBe('factura-titulo');
    expect(texto('#factura-titulo')).toBe('Factura B');
    const articulo = el().querySelector('article.factura-imprimible')!;
    expect(articulo.closest('.dialogo-fondo > [role="dialog"]')).not.toBeNull();
    expect(articulo.querySelector('app-factura-hoja')).not.toBeNull();
    const botones = Array.from(el().querySelectorAll('button'));
    expect(botones.map((b) => limpio(b.textContent))).toEqual(['Cerrar', 'Imprimir']);
    expect(botones.every((b) => !articulo.contains(b))).toBe(true);
    expect(articulo.contains(el().querySelector('#factura-titulo'))).toBe(false);
  });

  it('con `fiscal.letra` B y `fiscal.cae` cargados pero el documento X, no aparece ninguno: la hoja sale solo del documento', async () => {
    const base = ventaParaFactura();
    await abrir({
      ...base,
      fiscal: fiscalDe(documentoX(MARCA_SIMULADO), {
        letra: 'B',
        numero: 12,
        cae: '70417054367476',
        caeVencimiento: '2026-11-07',
        ambiente: 'SIMULADOR',
      }),
    });

    const todo = limpio(el().textContent);
    expect(texto('.letra .grande')).toBe('X');
    expect(texto('#factura-titulo')).toBe('Venta N.º 7');
    expect(todo).toContain('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(todo).toContain('SIMULADO — sin validez fiscal');
    expect(todo).not.toContain('70417054367476');
    expect(todo).not.toContain('CAE');
    expect(todo).not.toContain('00001-00000012');
    expect(todo).not.toContain('Comprobante Autorizado');
    expect(todo).not.toMatch(/Factura [ABC]\b/);
    expect(el().querySelector('svg.qr')).toBeNull();
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

  it('un Enter que cierra un escaneo no imprime (tampoco sobre el botón) y avisa', async () => {
    const esEscaneo = vi.fn((evento: KeyboardEvent) => evento.key === 'Enter');
    await abrir(ventaFacturada(), { esEscaneo });

    tecla('7');
    const enter = tecla('Enter', boton('Imprimir'));

    expect(esEscaneo).toHaveBeenCalledTimes(2);
    expect(enter.defaultPrevented).toBe(true);
    expect(imprimir).not.toHaveBeenCalled();
    expect(descartados).toBe(1);
  });

  it('el vuelto se ve en el diálogo, fuera de lo que se imprime; sin vuelto no hay', async () => {
    await abrir(ventaFacturada(), { vueltoCentavos: 56500 });

    const vuelto = el().querySelector('.vuelto')!;
    expect(limpio(vuelto.textContent)).toBe('Vuelto $ 565,00');
    expect(el().querySelector('article.factura-imprimible')!.contains(vuelto)).toBe(false);

    TestBed.resetTestingModule();
    await abrir();
    expect(el().querySelector('.vuelto')).toBeNull();
  });

  it('muestra el aviso del contenedor (el escaneo descartado)', async () => {
    await abrir(ventaFacturada(), { aviso: 'Cerrá el diálogo antes de escanear.' });

    expect(texto('[role="status"]')).toBe('Cerrá el diálogo antes de escanear.');
  });
});
