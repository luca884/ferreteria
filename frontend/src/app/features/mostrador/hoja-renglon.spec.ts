import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HojaRenglon } from './hoja-renglon';
import { RenglonTicket } from './ticket';

function renglon(parcial: Partial<RenglonTicket> = {}): RenglonTicket {
  return {
    articuloId: 1,
    codigoInterno: 'MEC-0088',
    descripcion: 'Mecha widia 8 mm',
    unidad: 'UNIDAD',
    precioCentavos: 480000,
    cantidadMilesimas: 1000,
    stockActual: 10,
    stockMinimo: 1,
    ...parcial,
  };
}

describe('HojaRenglon (movil-sectores T8a)', () => {
  let fixture: ComponentFixture<HojaRenglon>;
  let emitidas: string[];

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const acciones = () => Array.from(el().querySelectorAll<HTMLButtonElement>('.accion-hoja'));
  const accion = (texto: string) => acciones().find((b) => normal(b.textContent).startsWith(texto))!;
  /** «Cantidad» y, aparte, su línea chica «Ahora: 1» (en pantalla va debajo: es un bloque). */
  const partes = (b: HTMLElement) => [
    normal(b.querySelector('span')!.firstChild!.textContent),
    normal(b.querySelector('small')?.textContent),
  ];

  async function abrir(r: RenglonTicket = renglon()) {
    await TestBed.configureTestingModule({ imports: [HojaRenglon] }).compileComponents();
    fixture = TestBed.createComponent(HojaRenglon);
    emitidas = [];
    fixture.componentRef.setInput('renglon', r);
    fixture.componentInstance.cantidad.subscribe(() => emitidas.push('cantidad'));
    fixture.componentInstance.descuento.subscribe(() => emitidas.push('descuento'));
    fixture.componentInstance.quitar.subscribe(() => emitidas.push('quitar'));
    fixture.componentInstance.cerrar.subscribe(() => emitidas.push('cerrar'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('es una hoja inferior con la descripción de título y el código, la cantidad y el precio de subtítulo', async () => {
    await abrir();

    const hoja = el().querySelector('[role="dialog"].hoja-inferior')!;
    expect(hoja).not.toBeNull();
    expect(normal(hoja.querySelector('h2')!.textContent)).toBe('Mecha widia 8 mm');
    expect(normal(hoja.querySelector('.hoja-inferior-subtitulo')!.textContent)).toBe('MEC-0088 · 1 × $ 4.800,00');
  });

  it('ofrece las tres acciones del renglón, la última en rojo', async () => {
    await abrir();

    expect(acciones().map(partes)).toEqual([
      ['Cantidad', 'Ahora: 1'],
      ['Descuento', 'Hoy: sin descuento'],
      ['Quitar renglón', ''],
    ]);
    expect(accion('Quitar').classList.contains('peligro')).toBe(true);
  });

  it('con descuento propio dice cuánto, y la cantidad por metro lleva su coma', async () => {
    await abrir(renglon({ unidad: 'METRO', cantidadMilesimas: 2500, descuentoBp: 1050 }));

    expect(partes(accion('Cantidad'))).toEqual(['Cantidad', 'Ahora: 2,5']);
    expect(partes(accion('Descuento'))).toEqual(['Descuento', 'Hoy: 10,5 %']);
  });

  it('cada acción avisa y nada más', async () => {
    await abrir();

    accion('Cantidad').click();
    accion('Descuento').click();
    accion('Quitar').click();

    expect(emitidas).toEqual(['cantidad', 'descuento', 'quitar']);
  });

  it('un toque en el velo cierra', async () => {
    await abrir();

    el().querySelector<HTMLElement>('.hoja-inferior-fondo')!.click();

    expect(emitidas).toEqual(['cerrar']);
  });
});
