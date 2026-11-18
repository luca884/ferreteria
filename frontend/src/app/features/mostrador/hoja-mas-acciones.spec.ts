import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AccionBotonera } from './botonera';
import { HojaMasAcciones } from './hoja-mas-acciones';

const SIN_RENGLONES = 'Agregá un renglón para usar esta acción.';

describe('HojaMasAcciones (movil-sectores T8b)', () => {
  let fixture: ComponentFixture<HojaMasAcciones>;
  let emitidas: string[];

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const acciones = () => Array.from(el().querySelectorAll<HTMLButtonElement>('.accion-hoja'));
  const accion = (texto: string) => acciones().find((b) => normal(b.textContent).startsWith(texto))!;
  /** «Cliente» y, aparte, su línea chica («Consumidor Final»): en pantalla la chica va debajo, es un bloque. */
  const partes = (b: HTMLElement) => [
    normal(b.querySelector('span')!.firstChild!.textContent),
    normal(b.querySelector('small')?.textContent),
  ];

  async function abrir(
    entradas: {
      renglones?: number;
      totalCentavos?: number;
      cliente?: string | null;
      motivos?: Partial<Record<AccionBotonera, string>>;
    } = {},
  ) {
    await TestBed.configureTestingModule({ imports: [HojaMasAcciones] }).compileComponents();
    fixture = TestBed.createComponent(HojaMasAcciones);
    emitidas = [];
    fixture.componentRef.setInput('renglones', entradas.renglones ?? 3);
    fixture.componentRef.setInput('totalCentavos', entradas.totalCentavos ?? 827200);
    fixture.componentRef.setInput('cliente', entradas.cliente ?? null);
    fixture.componentRef.setInput('motivos', entradas.motivos ?? {});
    fixture.componentInstance.accion.subscribe((a) => emitidas.push(a));
    fixture.componentInstance.cerrar.subscribe(() => emitidas.push('cerrar'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('es una hoja inferior «Acciones del ticket» con los renglones y el total de subtítulo', async () => {
    await abrir();

    const hoja = el().querySelector('[role="dialog"].hoja-inferior')!;
    expect(hoja).not.toBeNull();
    expect(normal(hoja.querySelector('h2')!.textContent)).toBe('Acciones del ticket');
    expect(normal(hoja.querySelector('.hoja-inferior-subtitulo')!.textContent)).toBe('3 renglones · $ 8.272,00');
  });

  it('con un solo renglón el subtítulo va en singular', async () => {
    await abrir({ renglones: 1, totalCentavos: 10000 });

    expect(normal(el().querySelector('.hoja-inferior-subtitulo')!.textContent)).toBe('1 renglón · $ 100,00');
  });

  it('ofrece cliente, descuento total, presupuesto, remito y vaciar, y vaciar va última y en rojo', async () => {
    await abrir();

    expect(acciones().map(partes)).toEqual([
      ['Cliente', 'Consumidor Final'],
      ['Descuento total', 'Todo el ticket'],
      ['Presupuesto', 'Guardar sin cobrar'],
      ['Remito', 'Descuenta stock, no cobra'],
      ['Vaciar ticket', ''],
    ]);
    expect(acciones().map((b) => b.classList.contains('peligro'))).toEqual([false, false, false, false, true]);
  });

  it('el cliente del ticket se lee en la fila de Cliente', async () => {
    await abrir({ cliente: 'Ana Gómez' });

    expect(partes(accion('Cliente'))).toEqual(['Cliente', 'Ana Gómez']);
  });

  it('cada acción avisa cuál se eligió y nada más', async () => {
    await abrir();

    accion('Cliente').click();
    accion('Descuento total').click();
    accion('Presupuesto').click();
    accion('Remito').click();
    accion('Vaciar').click();

    expect(emitidas).toEqual(['cliente', 'descuentoTotal', 'presupuesto', 'remito', 'vaciar']);
    // Ninguna fila está apagada: no hay atributo aria-disabled.
    expect(acciones().every((b) => !b.hasAttribute('aria-disabled'))).toBe(true);
  });

  it('una acción apagada se ve apagada, dice su motivo en lugar de la ayuda y al tocarla no avisa nada', async () => {
    await abrir({
      renglones: 0,
      totalCentavos: 0,
      motivos: { descuentoTotal: SIN_RENGLONES, presupuesto: SIN_RENGLONES, remito: SIN_RENGLONES, vaciar: SIN_RENGLONES },
    });

    expect(acciones().map((b) => b.getAttribute('aria-disabled'))).toEqual([null, 'true', 'true', 'true', 'true']);
    expect(partes(accion('Descuento total'))).toEqual(['Descuento total', SIN_RENGLONES]);
    expect(partes(accion('Presupuesto'))).toEqual(['Presupuesto', SIN_RENGLONES]);
    expect(partes(accion('Remito'))).toEqual(['Remito', SIN_RENGLONES]);
    expect(partes(accion('Vaciar'))).toEqual(['Vaciar ticket', SIN_RENGLONES]);

    accion('Descuento total').click();
    accion('Presupuesto').click();
    accion('Remito').click();
    accion('Vaciar').click();
    expect(emitidas).toEqual([]);

    // Cliente anda con el ticket vacío, como hoy.
    accion('Cliente').click();
    expect(emitidas).toEqual(['cliente']);
  });

  it('un toque en el velo cierra', async () => {
    await abrir();

    el().querySelector<HTMLElement>('.hoja-inferior-fondo')!.click();

    expect(emitidas).toEqual(['cerrar']);
  });
});
