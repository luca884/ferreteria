import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ArticuloMostrador } from './mostrador.models';
import { ListaResultados } from './lista-resultados';

registerLocaleData(localeEsAr);

const NBSP = '\u00a0';

describe('ListaResultados', () => {
  const articulo = (id: number, cambios: Partial<ArticuloMostrador> = {}): ArticuloMostrador => ({
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 10,
    stockMinimo: 5,
    ubicacion: 'Pasillo 1',
    activo: true,
    ...cambios,
  });

  let fixture: ComponentFixture<ListaResultados>;
  let scrollIntoView: ReturnType<typeof vi.fn>;
  let elegidos: number[];

  async function crear(items: ArticuloMostrador[], seleccionado = 0, hayMas = false) {
    // jsdom no implementa scrollIntoView.
    scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView =
      scrollIntoView as unknown as typeof Element.prototype.scrollIntoView;
    await TestBed.configureTestingModule({
      imports: [ListaResultados],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(ListaResultados);
    elegidos = [];
    fixture.componentInstance.elegir.subscribe((i) => elegidos.push(i));
    fixture.componentRef.setInput('items', items);
    fixture.componentRef.setInput('seleccionado', seleccionado);
    fixture.componentRef.setInput('hayMas', hayMas);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('[role="option"]'));
  const texto = (selector: string, fila = 0) =>
    (filas()[fila].querySelector(selector)?.textContent ?? '').trim();

  it('es un listbox con una fila option por artículo, resaltando la seleccionada', async () => {
    await crear([articulo(1), articulo(2), articulo(3)], 1);

    expect(el().querySelector('[role="listbox"]')).not.toBeNull();
    expect(filas()).toHaveLength(3);
    expect(filas().map((f) => f.getAttribute('aria-selected'))).toEqual(['false', 'true', 'false']);
  });

  it('tiene encabezados de columnas, fuera del listbox y ocultos al lector de pantalla', async () => {
    await crear([articulo(1)]);

    const encabezado = el().querySelector('.encabezado')!;
    expect(Array.from(encabezado.children).map((c) => c.textContent!.trim())).toEqual([
      'Descripción',
      'Precio',
      'Stock',
      'Ubicación',
    ]);
    expect(encabezado.getAttribute('aria-hidden')).toBe('true');
    expect(el().querySelector('[role="listbox"]')!.contains(encabezado)).toBe(false);
  });

  it('cada fila tiene un id para aria-activedescendant', async () => {
    await crear([articulo(1), articulo(2)]);

    expect(filas().map((f) => f.id)).toEqual(['resultado-0', 'resultado-1']);
    expect(el().querySelector('[role="listbox"]')!.id).toBe('resultados');
  });

  it('muestra descripción, códigos, precio, stock con su unidad y ubicación', async () => {
    await crear([
      articulo(1, {
        codigoInterno: 'TOR-001',
        codigoBarras: '7790001',
        descripcion: 'Tornillo hexagonal 1/4',
        precioFinal: 1234.5,
        unidad: 'METRO',
        stockActual: 2.5,
        ubicacion: 'Pasillo 2',
      }),
    ]);

    expect(texto('.descripcion')).toBe('Tornillo hexagonal 1/4');
    expect(texto('.codigos')).toBe('TOR-001 · 7790001');
    expect(texto('.precio')).toBe(`$${NBSP}1.234,50`);
    expect(texto('.stock')).toBe('2,5 metros');
    expect(texto('.ubicacion')).toBe('Pasillo 2');
  });

  it('sin código de barras muestra solo el código interno, y sin ubicación un guion', async () => {
    await crear([articulo(1, { codigoInterno: 'MAR-007', codigoBarras: null, ubicacion: null })]);

    expect(texto('.codigos')).toBe('MAR-007');
    expect(texto('.ubicacion')).toBe('—');
  });

  it('debajo de los códigos dice desde cuándo está al día el costo (GDS-05); sin fecha, nada', async () => {
    await crear([articulo(1, { costoAl: '2026-05-20' }), articulo(2, { costoAl: null })]);

    expect(texto('.costo-al')).toBe('Costo al 20/05/2026');
    expect(filas()[1].querySelector('.costo-al')).toBeNull();
  });

  it('un dado de baja lleva la marca «Dado de baja» en texto, y un activo no', async () => {
    await crear([articulo(1), articulo(2, { activo: false })]);

    expect(filas()[0].querySelector('.baja')).toBeNull();
    expect(filas()[0].classList.contains('inactivo')).toBe(false);
    expect(texto('.baja', 1)).toBe('Dado de baja');
    expect(filas()[1].classList.contains('inactivo')).toBe(true);
  });

  it('marca el estado del stock de cada fila según estadoStock', async () => {
    await crear([
      articulo(1, { stockActual: 0, stockMinimo: 5 }),
      articulo(2, { stockActual: 3, stockMinimo: 5 }),
      articulo(3, { stockActual: 5, stockMinimo: 5 }),
    ]);

    const estados = filas().map((f) => f.querySelector('.stock')!.getAttribute('data-stock'));
    expect(estados).toEqual(['sin-stock', 'bajo-minimo', 'normal']);
  });

  it('un clic en una fila emite elegir con su índice', async () => {
    await crear([articulo(1), articulo(2), articulo(3)]);

    filas()[2].click();

    expect(elegidos).toEqual([2]);
  });

  it('con hayMas muestra el aviso junto a las filas', async () => {
    await crear([articulo(1), articulo(2)], 0, true);

    expect(filas()).toHaveLength(2);
    expect(el().textContent).toContain('Hay más resultados. Escribí más para afinar la búsqueda.');
  });

  it('sin hayMas no muestra el aviso', async () => {
    await crear([articulo(1), articulo(2)], 0, false);

    expect(el().textContent).not.toContain('Hay más resultados');
  });

  it('lleva la fila resaltada a la vista, con block nearest', async () => {
    await crear([articulo(1), articulo(2), articulo(3)], 0);
    scrollIntoView.mockClear();

    fixture.componentRef.setInput('seleccionado', 2);
    await refrescar();

    expect(scrollIntoView).toHaveBeenLastCalledWith({ block: 'nearest' });
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(filas()[2]);
  });
});
