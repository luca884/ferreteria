import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Unidad } from '../articulos/articulo.models';
import { RenglonTicket } from './ticket';
import { TicketLista } from './ticket-lista';

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

describe('TicketLista', () => {
  let fixture: ComponentFixture<TicketLista>;

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll('.renglon'));
  const texto = (selector: string, fila = 0) =>
    filas()[fila].querySelector(selector)!.textContent!.trim();
  const total = () => el().querySelector('.total')!.textContent!.trim();

  async function crear(renglones: RenglonTicket[], elegido: number | null = null) {
    await TestBed.configureTestingModule({
      imports: [TicketLista],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(TicketLista);
    fixture.componentRef.setInput('renglones', renglones);
    fixture.componentRef.setInput('elegido', elegido);
    fixture.detectChanges();
  }

  it('muestra descripción, cantidad, precio y total del renglón con monto', async () => {
    await crear([renglon()]);

    expect(texto('.descripcion')).toBe('Martillo');
    expect(texto('.cantidad')).toBe('3');
    expect(texto('.precio')).toBe(`$${NBSP}1.210,00`);
    expect(texto('.total-renglon')).toBe(`$${NBSP}3.630,00`);
  });

  it('la cantidad lleva la unidad: 2,5 METRO es «2,5 m»', async () => {
    await crear([renglon({ unidad: 'METRO', cantidadMilesimas: 2500 })]);

    expect(texto('.cantidad')).toBe('2,5 m');
  });

  it('KILO y LITRO llevan su abreviatura', async () => {
    await crear([
      renglon({ articuloId: 1, unidad: 'KILO', cantidadMilesimas: 1250 }),
      renglon({ articuloId: 2, unidad: 'LITRO', cantidadMilesimas: 4000 }),
    ]);

    expect(texto('.cantidad', 0)).toBe('1,25 kg');
    expect(texto('.cantidad', 1)).toBe('4 l');
  });

  it('el total del renglón redondea HALF_UP como el servidor (2,5 x 333,33 = 833,33)', async () => {
    await crear([renglon({ unidad: 'METRO', precioCentavos: 33333, cantidadMilesimas: 2500 })]);

    expect(texto('.total-renglon')).toBe(`$${NBSP}833,33`);
  });

  it('resalta el renglón elegido', async () => {
    await crear([renglon({ articuloId: 1 }), renglon({ articuloId: 2, descripcion: 'Pinza' })], 1);

    expect(filas()[0].classList.contains('elegido')).toBe(false);
    expect(filas()[1].classList.contains('elegido')).toBe(true);
    expect(filas()[1].getAttribute('aria-current')).toBe('true');
    expect(filas()[0].hasAttribute('aria-current')).toBe(false);
  });

  it('avisa «Sin stock suficiente» solo si la cantidad supera el stock visto', async () => {
    await crear([
      renglon({ articuloId: 1, cantidadMilesimas: 5000, stockActual: 2 }),
      renglon({ articuloId: 2, cantidadMilesimas: 5000, stockActual: 5 }),
    ]);

    expect(filas()[0].querySelector('.sin-stock')!.textContent!.trim()).toBe('Sin stock suficiente');
    expect(filas()[1].querySelector('.sin-stock')).toBeNull();
  });

  it('muestra el problema que marcó el servidor', async () => {
    await crear([renglon({ problema: '"Martillo" fue dado de baja. Sacalo del ticket para seguir.' })]);

    expect(texto('.problema')).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
  });

  it('el total grande es la suma de los renglones', async () => {
    await crear([
      renglon({ precioCentavos: 121000, cantidadMilesimas: 3000 }),
      renglon({ articuloId: 2, unidad: 'METRO', precioCentavos: 33333, cantidadMilesimas: 2500 }),
    ]);

    expect(total()).toBe(`$${NBSP}4.463,33`);
  });

  it('vacío: total $ 0,00 y mensaje de ticket vacío', async () => {
    await crear([]);

    expect(total()).toBe(`$${NBSP}0,00`);
    expect(el().textContent).toContain('El ticket está vacío.');
    expect(filas().length).toBe(0);
  });

  it('no tiene botón Vaciar: «Vaciar» vive solo en la botonera (R-038)', async () => {
    await crear([renglon()]);

    expect(el().querySelector('button')).toBeNull();
    expect(el().textContent).not.toContain('Vaciar');
  });

  it('no muestra costo ni margen', async () => {
    await crear([renglon()]);

    expect(el().textContent!.toLowerCase()).not.toMatch(/costo|margen/);
  });

  it('el stock y la cantidad no llevan signo $', async () => {
    await crear([renglon({ cantidadMilesimas: 5000, stockActual: 2 })]);

    expect(texto('.cantidad')).not.toContain('$');
    expect(filas()[0].querySelector('.sin-stock')!.textContent).not.toContain('$');
  });

  it('scrollIntoView sigue al renglón elegido', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView =
      scrollIntoView as unknown as typeof Element.prototype.scrollIntoView;
    await crear([renglon({ articuloId: 1 }), renglon({ articuloId: 2 })], 1);
    await fixture.whenStable();

    expect(scrollIntoView).toHaveBeenCalled();
  });
});
