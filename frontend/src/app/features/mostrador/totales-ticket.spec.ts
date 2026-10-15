import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Unidad } from '../articulos/articulo.models';
import { RenglonTicket } from './ticket';
import { TotalesTicket } from './totales-ticket';

registerLocaleData(localeEsAr);

const NBSP = ' ';

function renglon(parcial: Partial<RenglonTicket> = {}): RenglonTicket {
  return {
    articuloId: 1,
    codigoInterno: 'MAR-001',
    descripcion: 'Martillo',
    unidad: 'UNIDAD' as Unidad,
    precioCentavos: 121000,
    cantidadMilesimas: 3000,
    stockActual: 50,
    stockMinimo: 5,
    ...parcial,
  };
}

describe('TotalesTicket', () => {
  let fixture: ComponentFixture<TotalesTicket>;

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) =>
    el().querySelector(selector)!.textContent!.replace(/\s+/g, ' ').trim();

  async function crear(renglones: RenglonTicket[]) {
    await TestBed.configureTestingModule({
      imports: [TotalesTicket],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(TotalesTicket);
    fixture.componentRef.setInput('renglones', renglones);
    fixture.detectChanges();
  }

  it('el total grande es la suma de los renglones', async () => {
    await crear([
      renglon({ precioCentavos: 121000, cantidadMilesimas: 3000 }),
      renglon({ articuloId: 2, unidad: 'METRO', precioCentavos: 33333, cantidadMilesimas: 2500 }),
    ]);

    expect(el().querySelector('.total')!.textContent!.trim()).toBe(`$${NBSP}4.463,33`);
    expect(el().querySelector('.total')!.getAttribute('aria-label')).toBe('Total del ticket');
  });

  it('vacío: total $ 0,00 y 0 artículos · 0 renglones', async () => {
    await crear([]);

    expect(el().querySelector('.total')!.textContent!.trim()).toBe(`$${NBSP}0,00`);
    expect(texto('.conteo')).toBe('0 artículos · 0 renglones');
  });

  it('cuenta artículos y renglones como GDS', async () => {
    await crear([
      renglon({ cantidadMilesimas: 3000 }),
      renglon({ articuloId: 2, unidad: 'METRO', cantidadMilesimas: 2500 }),
    ]);

    expect(texto('.conteo')).toBe('4 artículos · 2 renglones');
  });

  it('en singular: 1 artículo · 1 renglón', async () => {
    await crear([renglon({ cantidadMilesimas: 1000 })]);

    expect(texto('.conteo')).toBe('1 artículo · 1 renglón');
  });

  it('no muestra costo ni margen', async () => {
    await crear([renglon()]);

    expect(el().textContent!.toLowerCase()).not.toMatch(/costo|margen/);
  });
});
