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

  it('un clic en un renglón avisa su índice para elegirlo', async () => {
    await crear([renglon({ articuloId: 1 }), renglon({ articuloId: 2 })]);
    const elegidos: number[] = [];
    fixture.componentInstance.elegir.subscribe((i) => elegidos.push(i));

    (filas()[1] as HTMLElement).click();

    expect(elegidos).toEqual([1]);
  });

  it('un renglón con descuento lo dice y su total sale ya descontado', async () => {
    await crear([renglon({ precioCentavos: 10000, cantidadMilesimas: 2000, descuentoBp: 1050 })]);

    expect(texto('.descuento')).toBe('10,5 % de descuento');
    expect(texto('.total-renglon')).toBe(`$${NBSP}179,00`);
  });

  it('un renglón sin descuento, o con 0, no muestra nada de descuento', async () => {
    await crear([renglon(), renglon({ articuloId: 2, descuentoBp: 0 })]);

    expect(el().querySelector('.descuento')).toBeNull();
  });

  it('la grilla tiene encabezados Cant. | Código | Descripción | P. unit. | Total', async () => {
    await crear([renglon()]);

    const encabezados = Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim());
    expect(encabezados).toEqual(['Cant.', 'Código', 'Descripción', 'P. unit.', 'Total']);
  });

  it('los encabezados se ven también con el ticket vacío', async () => {
    await crear([]);

    expect(el().querySelectorAll('thead th').length).toBe(5);
  });

  it('muestra el código interno de cada renglón', async () => {
    await crear([renglon(), renglon({ articuloId: 2, codigoInterno: 'PIN-002', descripcion: 'Pinza' })]);

    expect(texto('.codigo', 0)).toBe('MAR-001');
    expect(texto('.codigo', 1)).toBe('PIN-002');
  });

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

  it('el precio unitario dice por qué unidad es: «/ kg», «/ m», «/ l»; el total no (#36)', async () => {
    await crear([
      renglon({ articuloId: 1, unidad: 'KILO', precioCentavos: 400000, cantidadMilesimas: 1500 }),
      renglon({ articuloId: 2, unidad: 'METRO', precioCentavos: 90000, cantidadMilesimas: 2000 }),
      renglon({ articuloId: 3, unidad: 'LITRO', precioCentavos: 520000, cantidadMilesimas: 1000 }),
    ]);

    expect(texto('.precio', 0)).toBe(`$${NBSP}4.000,00 / kg`);
    expect(texto('.precio', 1)).toBe(`$${NBSP}900,00 / m`);
    expect(texto('.precio', 2)).toBe(`$${NBSP}5.200,00 / l`);
    expect(texto('.total-renglon', 0)).toBe(`$${NBSP}6.000,00`);
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

  it('en un ticket de un remito mira solo lo que sale en la venta: los 300 remitidos ya salieron (arreglos-confusos, S7)', async () => {
    await crear([
      renglon({ articuloId: 1, cantidadMilesimas: 300000, remitidoMilesimas: 300000, stockActual: 0 }),
      renglon({ articuloId: 2, cantidadMilesimas: 305000, remitidoMilesimas: 300000, stockActual: 4 }),
    ]);

    expect(filas()[0].querySelector('.sin-stock')).toBeNull();
    expect(filas()[0].classList.contains('con-faltante')).toBe(false);
    expect(filas()[1].querySelector('.sin-stock')!.textContent!.trim()).toBe('Sin stock suficiente');
    expect(filas()[1].classList.contains('con-faltante')).toBe(true);
  });

  it('muestra el problema que marcó el servidor', async () => {
    await crear([renglon({ problema: '"Martillo" fue dado de baja. Sacalo del ticket para seguir.' })]);

    expect(texto('.problema')).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
  });

  it('el problema de un renglón no es un role="alert" propio: lo anuncia el aviso del mostrador', async () => {
    await crear([
      renglon({ problema: 'Artículo dado de baja' }),
      renglon({ articuloId: 2, problema: 'Ya no está disponible' }),
    ]);

    expect(el().querySelectorAll('[role="alert"]')).toHaveLength(0);
    expect(el().querySelectorAll('[aria-live]')).toHaveLength(0);
  });

  it('vacío: mensaje de ticket vacío (el total vive en TotalesTicket)', async () => {
    await crear([]);

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
