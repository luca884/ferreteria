import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ArticuloMostrador } from './mostrador.models';
import { FichaArticulo } from './ficha-articulo';

registerLocaleData(localeEsAr);

const NBSP = '\u00a0';

describe('FichaArticulo', () => {
  const tornillo: ArticuloMostrador = {
    id: 1,
    codigoInterno: 'TOR-001',
    codigoBarras: '7790001',
    codigoProveedor: 'PROV-99',
    descripcion: 'Tornillo hexagonal 1/4',
    precioFinal: 1234.5,
    unidad: 'METRO',
    stockActual: 2.5,
    stockMinimo: 1,
    ubicacion: 'Pasillo 2',
    activo: true,
  };

  let fixture: ComponentFixture<FichaArticulo>;

  async function crear(articulo: ArticuloMostrador) {
    await TestBed.configureTestingModule({
      imports: [FichaArticulo],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(FichaArticulo);
    fixture.componentRef.setInput('articulo', articulo);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').trim();

  it('muestra descripción, códigos, precio, stock con su unidad y ubicación', async () => {
    await crear(tornillo);

    expect(texto('.descripcion')).toBe('Tornillo hexagonal 1/4');
    expect(texto('.codigo-interno')).toBe('TOR-001');
    expect(texto('.codigo-barras')).toBe('7790001');
    expect(texto('.precio')).toBe(`$${NBSP}1.234,50`);
    expect(texto('.stock')).toBe('2,5 metros');
    expect(texto('.ubicacion')).toBe('Pasillo 2');
  });

  it('sin código de barras ni ubicación muestra guiones', async () => {
    await crear({ ...tornillo, codigoBarras: null, ubicacion: null });

    expect(texto('.codigo-barras')).toBe('—');
    expect(texto('.ubicacion')).toBe('—');
  });

  it('no muestra costo ni margen', async () => {
    await crear(tornillo);

    expect(el().textContent).not.toMatch(/costo|margen/i);
  });

  it.each([
    ['sin-stock', 0, 5],
    ['sin-stock', -1, 5],
    ['bajo-minimo', 3, 5],
    ['normal', 5, 5],
    ['normal', 6, 5],
  ])(
    'marca el stock como %s con stock %d y mínimo %d',
    async (esperado, stockActual, stockMinimo) => {
      await crear({ ...tornillo, stockActual, stockMinimo });

      expect(el().querySelector('.stock')!.getAttribute('data-stock')).toBe(esperado);
    },
  );
});
