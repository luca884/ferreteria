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

  it('dice desde cuándo está al día el costo (GDS-05), sin mostrar el costo', async () => {
    await crear({ ...tornillo, costoAl: '2026-05-20' });

    expect(texto('.costo-al')).toBe('20/05/2026');
  });

  it('sin fecha de costo muestra un guion', async () => {
    await crear({ ...tornillo, costoAl: null });

    expect(texto('.costo-al')).toBe('—');
  });

  it('bloque 2, S5: muestra el código del proveedor', async () => {
    await crear(tornillo);

    expect(texto('.codigo-proveedor')).toBe('PROV-99');
  });

  it('ofertas, S3: en oferta muestra la marca, el precio de oferta y el normal tachado', async () => {
    await crear({ ...tornillo, precioFinal: 1000, enOferta: true, precioNormal: 1234.5 });

    expect(texto('.marca-oferta')).toBe('Oferta');
    expect(texto('.precio .precio-oferta')).toBe(`$${NBSP}1.000,00`);
    expect(texto('.precio s')).toBe(`$${NBSP}1.234,50`);
  });

  it('sin oferta no hay marca', async () => {
    await crear(tornillo);

    expect(el().querySelector('.marca-oferta')).toBeNull();
    expect(texto('.precio')).toBe(`$${NBSP}1.234,50`);
  });

  it('sin código de barras, del proveedor ni ubicación muestra guiones', async () => {
    await crear({ ...tornillo, codigoBarras: null, codigoProveedor: null, ubicacion: null });

    expect(texto('.codigo-proveedor')).toBe('—');
    expect(texto('.codigo-barras')).toBe('—');
    expect(texto('.ubicacion')).toBe('—');
  });

  it('no muestra costo ni margen: lo único de costo es la fecha «Costo al» (GDS-05)', async () => {
    await crear({ ...tornillo, costoAl: '2026-05-20' });

    expect(el().textContent!.replace('Costo al', '')).not.toMatch(/costo|margen/i);
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
