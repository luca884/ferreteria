import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { ConfirmacionCobro, CuentaDelCobro, DialogoCobro } from './dialogo-cobro';

const NBSP = ' ';

const forma = (
  id: number,
  nombre: string,
  medio: FormaPago['medio'],
  recargoPorcentaje = 0,
  descuentoPorcentaje = 0,
): FormaPago => ({
  id,
  nombre,
  medio,
  recargoPorcentaje,
  descuentoPorcentaje,
  activa: true,
  orden: id,
});

/** Las formas de siempre de una ferretería, sin recargo: Efectivo 1, Débito 2, Crédito 3, Transferencia 4. */
const FORMAS: FormaPago[] = [
  forma(1, 'Efectivo', 'EFECTIVO'),
  forma(2, 'Débito', 'DEBITO'),
  forma(3, 'Crédito', 'CREDITO'),
  forma(4, 'Transferencia', 'TRANSFERENCIA'),
];

describe('DialogoCobro', () => {
  let fixture: ComponentFixture<DialogoCobro>;
  let confirmadas: ConfirmacionCobro[];
  let canceladas: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const importe = () => el().querySelector<HTMLInputElement>('input:not([type="checkbox"])')!;
  const medio = (nombre: string) =>
    Array.from(el().querySelectorAll('button[role="radio"]')).find((b) =>
      b.textContent!.includes(nombre),
    ) as HTMLButtonElement;
  const elegidos = () =>
    Array.from(el().querySelectorAll('button[role="radio"][aria-checked="true"]')).map((b) =>
      b.textContent!.trim(),
    );
  const vuelto = () => el().querySelector('.vuelto')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;
  const faltante = () => el().querySelector('.falta')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;
  const error = () => el().querySelector('[role="alert"]')?.textContent?.trim() ?? null;

  function tecla(key: string, destino: Element = document.activeElement ?? el()): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function escribir(texto: string) {
    importe().value = texto;
    importe().dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  async function abrir(totalCentavos = 363000, formas: FormaPago[] = FORMAS) {
    await TestBed.configureTestingModule({ imports: [DialogoCobro] }).compileComponents();
    fixture = TestBed.createComponent(DialogoCobro);
    confirmadas = [];
    canceladas = 0;
    descartados = 0;
    fixture.componentRef.setInput('totalCentavos', totalCentavos);
    fixture.componentRef.setInput('formas', formas);
    fixture.componentInstance.confirmar.subscribe((c) => confirmadas.push(c));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
    await estabilizar();
  }

  /** La ferretería factura (o no se sabe todavía): el cajero elige con un clic (bloque 2, S4). */
  async function abrirEligiendo(totalCentavos = 363000, formas: FormaPago[] = FORMAS) {
    await abrir(totalCentavos, formas);
    fixture.componentRef.setInput('eligeFactura', true);
    await estabilizar();
  }

  const facturar = () => el().querySelector<HTMLButtonElement>('button.facturar')!;
  const sinFactura = () => el().querySelector<HTMLButtonElement>('button.sin-factura')!;

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('abre con el total, EFECTIVO elegido, el recibido igual al total y el foco en el importe', async () => {
    await abrir();

    expect(el().querySelector('[role="dialog"]')!.getAttribute('aria-modal')).toBe('true');
    expect(el().querySelector('.total')!.textContent!.trim()).toBe(`$${NBSP}3.630,00`);
    expect(elegidos()).toEqual(['Efectivo']);
    // El foco entra y le saca el «,00» para seguir tecleando (arreglos-compras, advisory 1).
    expect(importe().value).toBe('3.630');
    expect(document.activeElement).toBe(importe());
  });

  it('«Recibido» es un campo de plata: lleva el «$» adentro y completa los decimales al salir (arreglos-compras, S6)', async () => {
    await abrir();

    expect(importe().closest('app-campo-moneda')?.querySelector('.signo')?.textContent?.trim()).toBe('$');
    escribir('5000');
    importe().dispatchEvent(new Event('blur'));
    expect(importe().value).toBe('5.000,00');
  });

  it('la cabecera dice cuántos renglones se cobran', async () => {
    await abrir();
    const conteo = () => el().querySelector('.conteo')?.textContent?.trim() ?? null;

    expect(conteo()).toBeNull();

    fixture.componentRef.setInput('renglones', 3);
    await estabilizar();
    expect(conteo()).toBe('3 renglones');

    fixture.componentRef.setInput('renglones', 1);
    await estabilizar();
    expect(conteo()).toBe('1 renglón');
  });

  it('muestra las formas de pago y «Mixto» en orden', async () => {
    await abrir();

    const nombres = Array.from(el().querySelectorAll('button[role="radio"]')).map((b) =>
      b.textContent!.trim(),
    );
    expect(nombres).toEqual(['Efectivo', 'Débito', 'Crédito', 'Transferencia', 'Mixto']);
  });

  it('→ y ← ciclan los medios, con preventDefault', async () => {
    await abrir();

    const derecha = tecla('ArrowRight');
    await estabilizar();
    expect(elegidos()).toEqual(['Débito']);
    expect(derecha.defaultPrevented).toBe(true);

    tecla('ArrowRight');
    tecla('ArrowRight');
    tecla('ArrowRight');
    tecla('ArrowRight');
    await estabilizar();
    expect(elegidos()).toEqual(['Efectivo']);

    const izquierda = tecla('ArrowLeft');
    await estabilizar();
    expect(elegidos()).toEqual(['Mixto']);
    expect(izquierda.defaultPrevented).toBe(true);
  });

  it('el vuelto de 5.000,00 sobre 3.630,00 es $ 1.370,00', async () => {
    await abrir();

    escribir('5000');

    expect(vuelto()).toBe(`Vuelto $${NBSP}1.370,00`);
    expect(error()).toBeNull();
  });

  it('el recibido lleva la máscara: 50000 se ve 50.000 y se lee como cincuenta mil pesos; el punto no entra', async () => {
    await abrir();

    escribir('50000');
    expect(importe().value).toBe('50.000');
    expect(vuelto()).toBe(`Vuelto $${NBSP}46.370,00`);

    escribir('5.000');
    expect(importe().value).toBe('5.000');
    escribir('1.5');
    expect(importe().value).toBe('15');
    tecla('Enter');
    fixture.detectChanges();
    expect(faltante()).toBe(`Faltan $${NBSP}3.615,00`);
  });

  it('con el recibido igual al total el vuelto es $ 0,00', async () => {
    await abrir();

    expect(vuelto()).toBe(`Vuelto $${NBSP}0,00`);
  });

  it('un recibido menor al total avisa, no muestra vuelto y no confirma', async () => {
    await abrir();
    escribir('3000');

    tecla('Enter');
    fixture.detectChanges();

    expect(faltante()).toBe(`Faltan $${NBSP}630,00`);
    expect(vuelto()).toBeNull();
    expect(confirmadas).toEqual([]);
  });

  it('«Faltan» se anuncia con aria-live polite y no como role="status" (no se re-lee entero en cada tecla)', async () => {
    await abrir();
    escribir('3000');

    const falta = el().querySelector('.falta')!;
    expect(falta.hasAttribute('role')).toBe(false);
    expect(falta.getAttribute('aria-live')).toBe('polite');
  });

  it('un recibido inválido queda marcado y enlazado a su error', async () => {
    await abrir();
    expect(importe().hasAttribute('aria-invalid')).toBe(false);
    expect(importe().getAttribute('spellcheck')).toBe('false');

    escribir('abc');

    expect(importe().getAttribute('aria-invalid')).toBe('true');
    const id = importe().getAttribute('aria-describedby')!;
    expect(el().querySelector(`[id="${id}"]`)!.textContent!.trim()).toBe('Escribí el importe recibido.');
  });

  it('atrapa el foco: Tab en «Cobrar» vuelve al medio elegido y Shift+Tab desde ahí va a «Cobrar»', async () => {
    await abrir();
    const cobrar = el().querySelector('button.cobrar') as HTMLButtonElement;
    cobrar.focus();

    const evento = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    cobrar.dispatchEvent(evento);
    expect(document.activeElement).toBe(medio('Efectivo'));
    expect(evento.defaultPrevented).toBe(true);

    medio('Efectivo').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(cobrar);
  });

  it('mientras falta plata no hay otro mensaje de error: solo el «Faltan»', async () => {
    await abrir();
    escribir('1495');

    expect(faltante()).toBe(`Faltan $${NBSP}2.135,00`);
    expect(error()).toBeNull();
  });

  it('con un recibido de más de $ 1.000.000 sobre el total no deja cobrar (tope)', async () => {
    await abrir();
    escribir('50002395');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('El importe recibido es demasiado alto.');
    expect(vuelto()).toBeNull();
    expect(confirmadas).toEqual([]);
  });

  it('el tope es inclusivo: total + $ 1.000.000,00 todavía se cobra', async () => {
    await abrir();
    escribir('1003630');

    tecla('Enter');

    expect(error()).toBeNull();
    expect(confirmadas).toEqual([
      { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 100363000, recargoCentavos: 0 },
    ]);
  });

  it('un recibido que no es un número no confirma', async () => {
    await abrir();
    escribir('abc');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('Escribí el importe recibido.');
    expect(confirmadas).toEqual([]);
  });

  it('con otro medio no hay recibido ni vuelto y el foco va al botón del medio', async () => {
    await abrir();

    tecla('ArrowRight');
    await estabilizar();

    expect(importe()).toBeNull();
    expect(vuelto()).toBeNull();
    expect(document.activeElement).toBe(medio('Débito'));
  });

  it('volver a EFECTIVO enfoca el importe de nuevo', async () => {
    await abrir();
    tecla('ArrowRight');
    await estabilizar();

    tecla('ArrowLeft');
    await estabilizar();

    expect(document.activeElement).toBe(importe());
  });

  it('clic en un medio lo elige', async () => {
    await abrir();

    medio('Crédito').click();
    await estabilizar();

    expect(elegidos()).toEqual(['Crédito']);
  });

  it('Enter confirma en efectivo exacto con el recibido', async () => {
    await abrir();

    const evento = tecla('Enter');

    expect(confirmadas).toEqual([
      { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0 },
    ]);
    expect(evento.defaultPrevented).toBe(true);
  });

  describe('clic en el fondo', () => {
    it('no le saca el foco al diálogo: se cancela el mousedown del fondo, no el de los controles', async () => {
      await abrir();

      const sobreFondo = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      el().querySelector('.dialogo-fondo')!.dispatchEvent(sobreFondo);
      const sobreCampo = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      importe().dispatchEvent(sobreCampo);
      const sobreMedio = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      medio('Débito').dispatchEvent(sobreMedio);

      expect(sobreFondo.defaultPrevented).toBe(true);
      expect(sobreCampo.defaultPrevented).toBe(false);
      expect(sobreMedio.defaultPrevented).toBe(false);
    });

    it('un mousedown dentro del diálogo, en una zona sin controles, no se cancela (el foco va al diálogo)', async () => {
      await abrir();

      const sobreDialogo = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      el().querySelector('[role="dialog"]')!.dispatchEvent(sobreDialogo);

      expect(sobreDialogo.defaultPrevented).toBe(false);
    });

    it('el diálogo puede recibir el foco (tabindex -1), y con él Esc y Enter siguen andando', async () => {
      await abrir();
      const dialogo = el().querySelector<HTMLElement>('[role="dialog"]')!;
      expect(dialogo.getAttribute('tabindex')).toBe('-1');

      dialogo.focus();
      expect(document.activeElement).toBe(dialogo);
      tecla('Enter');
      tecla('Escape');

      expect(confirmadas).toHaveLength(1);
      expect(canceladas).toBe(1);
    });

  });

  describe('botones de forma con nombre largo', () => {
    const LARGO = 'Visa 6 cuotas sin interés con tarjeta bancaria';

    it('el botón deja partir el nombre en renglones: sin esto el texto desborda y pisa al vecino', async () => {
      await abrir(363000, [...FORMAS, forma(7, LARGO, 'CREDITO', 10)]);

      const boton = medio('Visa 6 cuotas');
      const estilo = getComputedStyle(boton);

      expect(boton.textContent).toContain(LARGO);
      expect(estilo.whiteSpace).toBe('normal');
      expect(estilo.overflowWrap).toBe('anywhere');
      expect(estilo.minWidth).toBe('0px');
    });

    it('la marca del recargo no se parte: «+10 %» queda junto aunque el nombre ocupe dos renglones', async () => {
      await abrir(363000, [...FORMAS, forma(7, LARGO, 'CREDITO', 10)]);

      const marca = medio('Visa 6 cuotas').querySelector('.marca-recargo')!;
      const estilo = getComputedStyle(marca);

      expect(estilo.whiteSpace).toBe('nowrap');
      expect(estilo.overflowWrap).toBe('normal');
    });
  });

  describe('qué comprobante sale (CU-07 paso 5, S3, S4)', () => {
    const casilla = () => el().querySelector('input[type="checkbox"]') as HTMLInputElement;
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const comprobante = () => normal(el().querySelector('.comprobante')?.textContent) || null;
    const autorizando = () =>
      Array.from(el().querySelectorAll('[role="status"]')).find((p) => normal(p.textContent) === 'Autorizando con ARCA…');

    it('sin rótulo (ferretería sin facturación) no dice nada, como hoy, ni siquiera en vuelo', async () => {
      await abrir();
      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();

      expect(el().querySelector('.comprobante')).toBeNull();
      expect(autorizando()).toBeUndefined();
    });

    it('muestra el rótulo con la marca del ambiente', async () => {
      await abrirEligiendo();
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura A · Ferretería Norte SRL', ambiente: 'SIMULADO' });
      await estabilizar();

      expect(comprobante()).toBe('Factura A · Ferretería Norte SRL SIMULADO');
      expect(normal(el().querySelector('.comprobante .ambiente')?.textContent)).toBe('SIMULADO');
    });

    it('en producción el rótulo va sin marca', async () => {
      await abrirEligiendo();
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura B · Consumidor Final', ambiente: null });
      await estabilizar();

      expect(comprobante()).toBe('Factura B · Consumidor Final');
      expect(el().querySelector('.comprobante .ambiente')).toBeNull();
    });

    it('mientras viaja una venta que se factura dice «Autorizando con ARCA…»; si se cobró sin factura, no', async () => {
      await abrirEligiendo();
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura B · Consumidor Final', ambiente: null });
      await estabilizar();
      expect(autorizando()).toBeUndefined();

      facturar().click();
      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();
      expect(autorizando()).toBeDefined();

      TestBed.resetTestingModule();
      await abrirEligiendo();
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura B · Consumidor Final', ambiente: null });
      await estabilizar();
      sinFactura().click();
      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();
      expect(autorizando()).toBeUndefined();
      expect(comprobante()).toBe('Sin factura');
    });
  });

  describe('facturar o no, con un clic (mejoras-ferreteros-bloque-2, S4)', () => {
    it('con facturación no hay casilla: hay «Cobrar sin factura» y «Cobrar y facturar con ARCA», y ninguno es Enter', async () => {
      await abrirEligiendo();

      expect(el().querySelector('input[type="checkbox"]')).toBeNull();
      expect(sinFactura().textContent?.trim()).toBe('Cobrar sin factura');
      expect(facturar().textContent?.trim()).toBe('Cobrar y facturar con ARCA');
      expect(el().querySelector('[data-tecla="Enter"]')).toBeNull();
      expect(el().querySelector('[aria-keyshortcuts="Enter"]')).toBeNull();
      expect(el().textContent).toContain('Elegí con un clic si la venta se factura.');
    });

    it('Enter no cobra: hay que elegir con el clic', async () => {
      await abrirEligiendo();

      tecla('Enter');

      expect(confirmadas).toEqual([]);
    });

    it('«Cobrar y facturar con ARCA» cobra sin la marca: la factura el servidor', async () => {
      await abrirEligiendo();

      facturar().click();

      expect(confirmadas).toEqual([{ formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0 }]);
    });

    it('«Cobrar sin factura» cobra con noFacturable true', async () => {
      await abrirEligiendo();

      sinFactura().click();

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0, noFacturable: true },
      ]);
    });

    it('en el pago mixto completo Enter tampoco cobra; el botón sí, con los pagos', async () => {
      await abrirEligiendo(10000);
      medio('Mixto').click();
      await estabilizar();
      const filas = Array.from(el().querySelectorAll<HTMLInputElement>('.fila-pago input'));
      filas[0].value = '40,00';
      filas[0].dispatchEvent(new Event('input', { bubbles: true }));
      filas[1].value = '60,00';
      filas[1].dispatchEvent(new Event('input', { bubbles: true }));
      await estabilizar();

      tecla('Enter', filas[1]);
      expect(confirmadas).toEqual([]);

      sinFactura().click();
      expect(confirmadas).toHaveLength(1);
      expect(confirmadas[0].noFacturable).toBe(true);
      expect(confirmadas[0].pagos).toHaveLength(2);
    });

    it('en el pago mixto que no suma el total, Enter no cobra pero avisa', async () => {
      await abrirEligiendo(10000);
      medio('Mixto').click();
      await estabilizar();
      const filas = Array.from(el().querySelectorAll<HTMLInputElement>('.fila-pago input'));
      const ultima = filas[filas.length - 1];
      ultima.value = '40,00';
      ultima.dispatchEvent(new Event('input', { bubbles: true }));
      await estabilizar();

      tecla('Enter', ultima);
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(error()).toBe('Los pagos tienen que sumar el total.');
    });

    it('en el pago mixto que se pasa del total, Enter no cobra pero avisa', async () => {
      await abrirEligiendo(10000);
      medio('Mixto').click();
      await estabilizar();
      const filas = Array.from(el().querySelectorAll<HTMLInputElement>('.fila-pago input'));
      filas[0].value = '150,00';
      filas[0].dispatchEvent(new Event('input', { bubbles: true }));
      await estabilizar();

      tecla('Enter', filas[0]);
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(error()).toBe('Los pagos tienen que sumar el total.');
    });

    it('un segundo clic mientras se cobra no emite', async () => {
      await abrirEligiendo();

      facturar().click();
      sinFactura().click();

      expect(confirmadas).toHaveLength(1);
    });

    it('sin facturación (el servidor dijo que no factura) queda «Cobrar» con Enter y lo avisa', async () => {
      await abrir();

      expect(el().querySelector('button.sin-factura')).toBeNull();
      expect(el().querySelector('button.facturar')).toBeNull();
      expect(el().querySelector('button.cobrar')?.getAttribute('data-tecla')).toBe('Enter');
      expect(el().textContent).toContain('Sin factura: la facturación con ARCA no está activa.');
      tecla('Enter');
      expect(confirmadas).toHaveLength(1);
    });

    // Regresiones del verificador: Enter con el foco en un botón tampoco cobra, y no se cobra dos veces.
    it('Enter con el foco en «Cobrar y facturar con ARCA» no cobra y frena la acción del botón', async () => {
      await abrirEligiendo();
      facturar().focus();

      const evento = tecla('Enter', facturar());

      expect(evento.defaultPrevented).toBe(true);
      expect(confirmadas).toEqual([]);
    });

    it('Enter con el foco en «Cobrar sin factura» no cobra', async () => {
      await abrirEligiendo();
      sinFactura().focus();

      const evento = tecla('Enter', sinFactura());

      expect(evento.defaultPrevented).toBe(true);
      expect(confirmadas).toEqual([]);
    });

    it('un clic en «Cobrar y facturar» después de uno en «sin factura» no cobra dos veces', async () => {
      await abrirEligiendo();

      sinFactura().click();
      facturar().click();

      expect(confirmadas).toHaveLength(1);
      expect(confirmadas[0].noFacturable).toBe(true);
    });
  });

  describe('no facturable sin facturación con ARCA (NF-003)', () => {
    const casilla = () => el().querySelector('input[type="checkbox"]') as HTMLInputElement;

    it('trae la casilla «No facturable» sin tildar', async () => {
      await abrir();

      expect(casilla()).not.toBeNull();
      expect(casilla().checked).toBe(false);
      expect(el().textContent).toContain('No facturable');
    });

    it('sin tildar no manda la marca: la confirmación es la de siempre', async () => {
      await abrir();

      tecla('Enter');

      expect(confirmadas).toHaveLength(1);
      expect('noFacturable' in confirmadas[0]).toBe(false);
    });

    it('tildada, Enter confirma con noFacturable true y el resto igual', async () => {
      await abrir();
      casilla().click();
      await estabilizar();

      tecla('Enter');

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0, noFacturable: true },
      ]);
    });

    it('tildar y destildar vuelve a no mandar la marca', async () => {
      await abrir();
      casilla().click();
      casilla().click();
      await estabilizar();

      tecla('Enter');

      expect('noFacturable' in confirmadas[0]).toBe(false);
    });

    it('el pago mixto también manda la marca', async () => {
      await abrir(10000);
      medio('Mixto').click();
      await estabilizar();
      const filas = Array.from(el().querySelectorAll<HTMLInputElement>('.fila-pago input'));
      filas[0].value = '40,00';
      filas[0].dispatchEvent(new Event('input', { bubbles: true }));
      filas[1].value = '60,00';
      filas[1].dispatchEvent(new Event('input', { bubbles: true }));
      casilla().click();
      await estabilizar();

      tecla('Enter', filas[1]);

      expect(confirmadas).toHaveLength(1);
      expect(confirmadas[0].noFacturable).toBe(true);
      expect(confirmadas[0].pagos).toHaveLength(2);
    });
  });

  it('Enter confirma con otro medio sin recibido', async () => {
    await abrir();
    tecla('ArrowRight');
    tecla('ArrowRight');
    await estabilizar();

    tecla('Enter');

    expect(confirmadas).toEqual([
      { formaPagoId: 3, medio: 'CREDITO', recibidoCentavos: null, recargoCentavos: 0 },
    ]);
  });

  it('un segundo Enter mientras se cobra no emite', async () => {
    await abrir();

    tecla('Enter');
    tecla('Enter');

    expect(confirmadas.length).toBe(1);
  });

  it('cuando termina el cobro (error) se puede confirmar de nuevo', async () => {
    await abrir();
    tecla('Enter');
    fixture.componentRef.setInput('enVuelo', true);
    await estabilizar();
    fixture.componentRef.setInput('enVuelo', false);
    await estabilizar();

    tecla('Enter');

    expect(confirmadas.length).toBe(2);
  });

  it('si el total cambia (409) y el recibido era el total, acompaña al total nuevo', async () => {
    await abrir();

    fixture.componentRef.setInput('totalCentavos', 365000);
    await estabilizar();

    expect(el().querySelector('.total')!.textContent!.trim()).toBe(`$${NBSP}3.650,00`);
    expect(importe().value).toBe('3.650,00');
  });

  it('muestra el aviso que le pasa el contenedor', async () => {
    await abrir();

    fixture.componentRef.setInput('aviso', 'Cambió el precio de "Martillo": ahora $ 1.650,00.');
    await estabilizar();

    expect(el().querySelector('.aviso')!.textContent).toContain('Cambió el precio de "Martillo"');
  });

  it('Esc cierra', async () => {
    await abrir();

    tecla('Escape');

    expect(canceladas).toBe(1);
    expect(confirmadas).toEqual([]);
  });

  it('el botón Cancelar cierra', async () => {
    await abrir();

    (el().querySelector('button.cancelar') as HTMLButtonElement).click();

    expect(canceladas).toBe(1);
  });

  it('una ráfaga del lector que termina en Enter no confirma y avisa', async () => {
    await abrir();

    for (const c of '7790001') {
      vi.advanceTimersByTime(5);
      tecla(c);
    }
    vi.advanceTimersByTime(5);
    const evento = tecla('Enter');

    expect(confirmadas).toEqual([]);
    expect(descartados).toBe(1);
    expect(evento.defaultPrevented).toBe(true);
  });
  describe('pago mixto (GDS-07)', () => {
    const monto = (id: number) => el().querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;
    const resumen = () => el().querySelector('.resumen-mixto')?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

    function escribirMonto(id: number, texto: string) {
      monto(id).value = texto;
      monto(id).dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
    }

    async function abrirMixto(total = 363000, formas: FormaPago[] = FORMAS) {
      await abrir(total, formas);
      medio('Mixto').click();
      await estabilizar();
    }

    it('cada monto del mixto es un campo de plata: lleva el «$» adentro y completa los decimales al salir', async () => {
      await abrirMixto();

      expect(monto(1).closest('app-campo-moneda')?.querySelector('.signo')?.textContent?.trim()).toBe('$');
      escribirMonto(1, '1500');
      monto(1).dispatchEvent(new Event('blur'));
      expect(monto(1).value).toBe('1.500,00');
      expect(resumen()).toBe('Falta $ 2.130,00');
    });

    it('«Mixto» muestra una fila por forma, sin el recibido, con el foco en efectivo y todo por cobrar', async () => {
      await abrirMixto();

      expect(elegidos()).toEqual(['Mixto']);
      expect(el().querySelectorAll('input[data-forma]')).toHaveLength(4);
      expect(el().querySelector('.recibido')).toBeNull();
      expect(document.activeElement).toBe(monto(1));
      expect(resumen()).toBe('Falta $ 3.630,00');
    });

    it('el monto de cada forma lleva la máscara: 50000 se ve 50.000 y descuenta cincuenta mil', async () => {
      await abrirMixto(10000000);

      escribirMonto(1, '50000');

      expect(monto(1).value).toBe('50.000');
      expect(resumen()).toBe('Falta $ 50.000,00');
      escribirMonto(1, '1.5');
      expect(monto(1).value).toBe('15');
    });

    it('al cargar una parte, lo que falta baja', async () => {
      await abrirMixto();

      escribirMonto(1, '1000');

      expect(resumen()).toBe('Falta $ 2.630,00');
    });

    it('Enter en una fila pasa a la siguiente y le sugiere el resto', async () => {
      await abrirMixto();
      escribirMonto(1, '1000');

      const evento = tecla('Enter');
      await estabilizar();

      expect(evento.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(monto(2));
      expect(monto(2).value).toBe('2.630,00');
      expect(resumen()).toBe('Completo');
      expect(confirmadas).toEqual([]);
    });

    it('Tab es la navegación normal: no sugiere el resto (como GDS, G53: lo sugiere Enter; QA 05/11 B-4)', async () => {
      await abrirMixto();
      escribirMonto(1, '1000');

      const evento = tecla('Tab');
      await estabilizar();

      expect(evento.defaultPrevented).toBe(false);
      expect(monto(2).value).toBe('');
      expect(confirmadas).toEqual([]);
    });

    it('con todo cubierto, Enter confirma los pagos en orden y sin forma única ni recibido', async () => {
      await abrirMixto();
      escribirMonto(1, '1000');
      tecla('Enter');
      await estabilizar();

      tecla('Enter');

      expect(confirmadas).toEqual([
        {
          formaPagoId: null,
          medio: null,
          recibidoCentavos: null,
          recargoCentavos: 0,
          pagos: [
            { formaPagoId: 1, centavos: 100000 },
            { formaPagoId: 2, centavos: 263000 },
          ],
        },
      ]);
    });

    it('las filas en blanco o en 0 no viajan', async () => {
      await abrirMixto(10000);
      escribirMonto(1, '0');
      escribirMonto(3, '100');

      (el().querySelector('.cobrar') as HTMLButtonElement).click();

      expect(confirmadas[0].pagos).toEqual([{ formaPagoId: 3, centavos: 10000 }]);
    });

    it('si falta, Cobrar no confirma y lo dice', async () => {
      await abrirMixto();
      escribirMonto(1, '1000');

      (el().querySelector('.cobrar') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(error()).toBe('Los pagos tienen que sumar el total.');
    });

    it('si los montos se pasan del total avisa cuánto y no confirma', async () => {
      await abrirMixto();
      escribirMonto(1, '5000');
      escribirMonto(2, '100');

      expect(resumen()).toBe('Se pasa $ 1.470,00');
      (el().querySelector('.cobrar') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(error()).toBe('Los pagos tienen que sumar el total.');
    });

    it('un texto que no es número no entra al monto: queda vacío y no confirma', async () => {
      await abrirMixto();
      escribirMonto(1, 'abc');

      expect(monto(1).value).toBe('');
      expect(confirmadas).toEqual([]);
      expect(resumen()).toBe('Falta $ 3.630,00');
    });

    it('una sola forma cargada por el total confirma igual (es lo mismo que una forma única)', async () => {
      await abrirMixto(10000);
      escribirMonto(4, '100');

      (el().querySelector('.cobrar') as HTMLButtonElement).click();

      expect(confirmadas[0].pagos).toEqual([{ formaPagoId: 4, centavos: 10000 }]);
    });

    it('un escaneo con el pago mixto abierto no confirma y avisa', async () => {
      await abrirMixto();
      for (const c of '7790001') {
        vi.advanceTimersByTime(5);
        tecla(c);
      }
      vi.advanceTimersByTime(5);

      tecla('Enter');

      expect(confirmadas).toEqual([]);
      expect(descartados).toBe(1);
    });
  });

  describe('formas de pago con descuento (descuento en efectivo)', () => {
    const contado = forma(1, 'Contado', 'EFECTIVO', 0, 10);
    const conDescuento = [contado, forma(2, 'Débito', 'DEBITO'), forma(7, 'Visa', 'CREDITO', 10)];
    const descuento = () =>
      el().querySelector('.descuento-forma')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;
    const recargo = () => el().querySelector('.recargo')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;
    const total = () => el().querySelector('.total')!.textContent!.trim();
    const monto = (id: number) => el().querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;

    function escribirMonto(id: number, texto: string) {
      monto(id).value = texto;
      monto(id).dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
    }

    it('la opción lleva la marca «−10 %» y la de recargo sigue con «+10 %»', async () => {
      await abrir(10000, conDescuento);

      const nombres = Array.from(el().querySelectorAll('button[role="radio"]')).map((b) =>
        b.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(nombres).toEqual(['Contado −10 %', 'Débito', 'Visa +10 %', 'Mixto']);
      expect(medio('Contado').querySelector('.marca-descuento')).not.toBeNull();
    });

    it('en efectivo con 10 %: la cabecera muestra el total descontado, la línea del descuento y el recibido arranca ahí', async () => {
      await abrir(10000, conDescuento);

      expect(total()).toBe(`$${NBSP}90,00`);
      expect(descuento()).toBe(`Descuento por forma de pago (10 %) −$${NBSP}10,00`);
      expect(recargo()).toBeNull();
      expect(importe().value).toBe('90');
      escribir('100');
      expect(vuelto()).toBe(`Vuelto $${NBSP}10,00`);
    });

    it('el descuento se redondea a centavos HALF_UP lejos del cero, igual que el servidor', async () => {
      await abrir(3335, conDescuento);

      // 33,35 x 10 % = 3,335 -> 3,34
      expect(descuento()).toBe(`Descuento por forma de pago (10 %) −$${NBSP}3,34`);
      expect(total()).toBe(`$${NBSP}30,01`);
    });

    it('al confirmar emite el descuento como recargo negativo en centavos', async () => {
      await abrir(10000, conDescuento);

      tecla('Enter');

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 9000, recargoCentavos: -1000 },
      ]);
    });

    it('pago mixto: el descuento va solo sobre la parte en efectivo y se compensa con el recargo de otra', async () => {
      await abrir(10000, conDescuento);
      medio('Mixto').click();
      await estabilizar();

      escribirMonto(1, '60');
      escribirMonto(2, '40');
      expect(descuento()).toBe(`Descuento por forma de pago −$${NBSP}6,00`);
      expect(total()).toBe(`$${NBSP}94,00`);

      escribirMonto(2, '');
      escribirMonto(1, '40');
      escribirMonto(7, '60');
      expect(descuento()).toBeNull();
      expect(recargo()).toBe(`Recargo $${NBSP}2,00`);
      expect(total()).toBe(`$${NBSP}102,00`);
    });
  });

  describe('formas de pago con recargo (GDS-11)', () => {
    const visa6 = forma(7, 'Visa 6 cuotas', 'CREDITO', 10);
    const formasConVisa = [...FORMAS, visa6];
    const recargo = () => el().querySelector('.recargo')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;
    const total = () => el().querySelector('.total')!.textContent!.trim();
    const monto = (id: number) => el().querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;
    const resumen = () => el().querySelector('.resumen-mixto')?.textContent?.replace(/[ \t\r\n]+/g, ' ').trim() ?? null;

    function escribirMonto(id: number, texto: string) {
      monto(id).value = texto;
      monto(id).dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
    }

    it('las opciones salen de las formas que le pasan, en su orden, y «Mixto» al final', async () => {
      await abrir(363000, formasConVisa);

      const nombres = Array.from(el().querySelectorAll('button[role="radio"]')).map((b) =>
        b.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(nombres).toEqual(['Efectivo', 'Débito', 'Crédito', 'Transferencia', 'Visa 6 cuotas +10 %', 'Mixto']);
    });

    it('abre en la primera forma en efectivo, aunque no sea la primera de la lista', async () => {
      await abrir(10000, [forma(5, 'Débito', 'DEBITO'), forma(6, 'Contado', 'EFECTIVO'), forma(1, 'Efectivo', 'EFECTIVO')]);

      expect(elegidos()).toEqual(['Contado']);
      expect(document.activeElement).toBe(importe());
    });

    it('sin ninguna forma en efectivo abre en la primera', async () => {
      await abrir(10000, [forma(5, 'Débito', 'DEBITO'), forma(3, 'Crédito', 'CREDITO')]);

      expect(elegidos()).toEqual(['Débito']);
    });

    it('sin recargo no se muestra la línea de recargo', async () => {
      await abrir();

      expect(recargo()).toBeNull();
    });

    it('con una forma con recargo, la cabecera muestra el total a cobrar y aparece el recargo', async () => {
      await abrir(363000, formasConVisa);

      medio('Visa 6 cuotas').click();
      await estabilizar();

      expect(total()).toBe(`$${NBSP}3.993,00`);
      expect(recargo()).toBe(`Recargo (10 %) $${NBSP}363,00`);
    });

    it('el recargo se calcula sobre el total sin recargo y a centavos HALF_UP', async () => {
      await abrir(3333, formasConVisa);

      medio('Visa 6 cuotas').click();
      await estabilizar();

      expect(recargo()).toBe(`Recargo (10 %) $${NBSP}3,33`);
      expect(total()).toBe(`$${NBSP}36,66`);
    });

    it('al confirmar con esa forma emite su id, el medio y el recargo en centavos', async () => {
      await abrir(363000, formasConVisa);
      medio('Visa 6 cuotas').click();
      await estabilizar();

      tecla('Enter');

      expect(confirmadas).toEqual([
        { formaPagoId: 7, medio: 'CREDITO', recibidoCentavos: null, recargoCentavos: 36300 },
      ]);
    });

    it('una forma en efectivo con recargo: el recibido arranca en el total a cobrar y el vuelto sale de ahi', async () => {
      await abrir(10000, [forma(1, 'Efectivo', 'EFECTIVO', 5)]);

      expect(importe().value).toBe('105');
      escribir('200');
      expect(vuelto()).toBe(`Vuelto $${NBSP}95,00`);
      tecla('Enter');

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 20000, recargoCentavos: 500 },
      ]);
    });

    it('con una forma que no es efectivo no hay recibido ni vuelto aunque tenga recargo', async () => {
      await abrir(363000, formasConVisa);

      medio('Visa 6 cuotas').click();
      await estabilizar();

      expect(importe()).toBeNull();
      expect(vuelto()).toBeNull();
    });

    it('si el recargo de la forma cambia mientras el dialogo esta abierto, el total a cobrar lo sigue', async () => {
      await abrir(363000, formasConVisa);
      medio('Visa 6 cuotas').click();
      await estabilizar();

      fixture.componentRef.setInput('formas', [...FORMAS, forma(7, 'Visa 6 cuotas', 'CREDITO', 25)]);
      await estabilizar();

      expect(total()).toBe(`$${NBSP}4.537,50`);
      expect(recargo()).toBe(`Recargo (25 %) $${NBSP}907,50`);
    });

    it('si la forma elegida deja de existir mientras esta abierto, vuelve a la primera en efectivo', async () => {
      await abrir(363000, formasConVisa);
      medio('Visa 6 cuotas').click();
      await estabilizar();

      fixture.componentRef.setInput('formas', FORMAS);
      await estabilizar();

      expect(elegidos()).toEqual(['Efectivo']);
      expect(total()).toBe(`$${NBSP}3.630,00`);
    });

    it('sin ninguna forma no hay opciones de pago y Cobrar no confirma', async () => {
      await abrir(363000, []);

      (el().querySelector('button.cobrar') as HTMLButtonElement).click();
      tecla('Enter');

      expect(el().querySelectorAll('button[role="radio"]')).toHaveLength(1);
      expect(confirmadas).toEqual([]);
    });

    describe('pago mixto', () => {
      async function abrirMixtoConVisa(total = 363000) {
        await abrir(total, formasConVisa);
        medio('Mixto').click();
        await estabilizar();
      }

      it('una fila por cada forma, con el nombre de la forma', async () => {
        await abrirMixtoConVisa();

        expect(el().querySelectorAll('input[data-forma]')).toHaveLength(5);
        expect(el().querySelector('label.fila-pago:last-of-type')?.textContent?.replace(/\s+/g, ' ').trim()).toContain(
          'Visa 6 cuotas',
        );
      });

      it('el recargo va solo sobre la parte de la forma que lo tiene y el resumen cuenta contra el total sin recargo', async () => {
        await abrirMixtoConVisa(10000);

        escribirMonto(1, '40');
        escribirMonto(7, '60');

        expect(resumen()).toBe('Completo');
        expect(recargo()).toBe(`Recargo $${NBSP}6,00`);
        expect(total()).toBe(`$${NBSP}106,00`);
      });

      it('mientras falta, el resumen dice cuanto y el total a cobrar ya suma el recargo de lo cargado', async () => {
        await abrirMixtoConVisa(10000);

        escribirMonto(7, '50');

        expect(resumen()).toBe(`Falta $${NBSP}50,00`);
        expect(recargo()).toBe(`Recargo $${NBSP}5,00`);
        expect(total()).toBe(`$${NBSP}105,00`);
      });

      it('confirma las partes sin recargo, con el recargo total en centavos', async () => {
        await abrirMixtoConVisa(10000);
        escribirMonto(1, '40');
        escribirMonto(7, '60');

        (el().querySelector('.cobrar') as HTMLButtonElement).click();

        expect(confirmadas).toEqual([
          {
            formaPagoId: null,
            medio: null,
            recibidoCentavos: null,
            recargoCentavos: 600,
            pagos: [
              { formaPagoId: 1, centavos: 4000 },
              { formaPagoId: 7, centavos: 6000 },
            ],
          },
        ]);
      });

      it('el recargo del mixto suma el de cada parte redondeado, igual que el servidor', async () => {
        await abrir(3333, [forma(1, 'Efectivo', 'EFECTIVO'), forma(2, 'Visa', 'CREDITO', 10), forma(3, 'Naranja', 'CREDITO', 10)]);
        medio('Mixto').click();
        await estabilizar();

        escribirMonto(2, '16,65');
        escribirMonto(3, '16,68');

        // 16,65 x 10 % = 1,665 -> 1,67 y 16,68 x 10 % = 1,668 -> 1,67: 3,34 (no 3,33 del total x 10 %)
        expect(recargo()).toBe(`Recargo $${NBSP}3,34`);
        expect(resumen()).toBe('Completo');
      });

      it('las filas siguen ahi si cambia la lista de formas mientras esta abierto', async () => {
        await abrirMixtoConVisa(10000);
        escribirMonto(1, '40');

        fixture.componentRef.setInput('formas', [...formasConVisa, forma(8, 'Cabal', 'DEBITO')]);
        await estabilizar();

        expect(el().querySelectorAll('input[data-forma]')).toHaveLength(6);
        expect(monto(1).value).toBe('40');
      });
    });
  });
  describe('cuenta corriente (CC-005, CC-006, CC-007, CC-013, CC-017)', () => {
    const CC = forma(5, 'Cuenta corriente', 'CUENTA_CORRIENTE');
    const FORMAS_CC = [...FORMAS, CC];
    const MIXTO_OPCION = 'Mixto';
    /** Los espacios duros del formato de moneda quedan como espacios comunes. */
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

    const opcionCC = () => medio('Cuenta corriente');
    const monto = (id: number) => el().querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;
    const razonDe = (boton: HTMLElement) => {
      const id = boton.getAttribute('aria-describedby');
      return id ? normal(el().querySelector(`#${id}`)?.textContent) : null;
    };
    const textoDelDialogo = () => normal(el().querySelector('[role="dialog"]')?.textContent);
    const botonCobrar = () => el().querySelector<HTMLButtonElement>('button.cobrar')!;
    const lineaCuenta = () => normal(el().querySelector('.cuenta-corriente')?.textContent);

    type Cuenta = CuentaDelCobro;

    interface Escenario {
      cliente?: string | null;
      cuenta?: Cuenta | null;
      esDueno?: boolean;
      avisoLimite?: unknown;
      total?: number;
    }

    async function abrirCC({ cliente = 'Ana Gómez', cuenta = null, esDueno = false, avisoLimite = null, total = 363000 }: Escenario = {}) {
      await abrir(total, FORMAS_CC);
      fixture.componentRef.setInput('clienteNombre', cliente);
      fixture.componentRef.setInput('cuenta', cuenta);
      fixture.componentRef.setInput('esDueno', esDueno);
      fixture.componentRef.setInput('avisoLimite', avisoLimite);
      await estabilizar();
    }

    const listo = (c: { habilitada: boolean; alcanza?: boolean; limite?: number; saldo?: number; disponible?: number }) =>
      ({ estado: 'listo', cuenta: c }) as Cuenta;

    /** Lo que ve el Vendedor: nada de cifras. */
    const SIN_CIFRAS_ALCANZA = listo({ habilitada: true, alcanza: true });
    const SIN_CIFRAS_NO_ALCANZA = listo({ habilitada: true, alcanza: false });
    /** Lo que ve el Dueño: límite 5.000, saldo 3.000, disponible 2.000; la venta de 3.630 se pasa por 1.630. */
    const DUENO_NO_ALCANZA = listo({ habilitada: true, alcanza: false, limite: 5000, saldo: 3000, disponible: 2000 });
    const DUENO_ALCANZA = listo({ habilitada: true, alcanza: true, limite: 10000, saldo: 3000, disponible: 7000 });

    describe('la opción «Cuenta corriente» deshabilitada, con su razón', () => {
      it('sin cliente: aria-disabled, razón visible enlazada con aria-describedby y sin el atributo disabled', async () => {
        await abrirCC({ cliente: null });

        expect(opcionCC().getAttribute('aria-disabled')).toBe('true');
        expect(opcionCC().hasAttribute('disabled')).toBe(false);
        expect(razonDe(opcionCC())).toBe('Elegí un cliente (F9) para vender a cuenta corriente.');
      });

      it('un clic en la deshabilitada no la elige', async () => {
        await abrirCC({ cliente: null });

        opcionCC().click();
        await estabilizar();

        expect(elegidos()).toEqual(['Efectivo']);
      });

      it('con el cliente pero sin dato de la cuenta: «Verificando la cuenta corriente…»', async () => {
        await abrirCC({ cuenta: null });

        expect(opcionCC().getAttribute('aria-disabled')).toBe('true');
        expect(razonDe(opcionCC())).toBe('Verificando la cuenta corriente…');
      });

      it('cargando la cuenta: «Verificando la cuenta corriente…»', async () => {
        await abrirCC({ cuenta: { estado: 'cargando' } as Cuenta });

        expect(razonDe(opcionCC())).toBe('Verificando la cuenta corriente…');
      });

      it('falla cerrada: si la cuenta no se pudo leer, no se ofrece y se explica', async () => {
        await abrirCC({ cuenta: { estado: 'error' } as Cuenta });

        expect(opcionCC().getAttribute('aria-disabled')).toBe('true');
        expect(razonDe(opcionCC())).toBe('No se pudo verificar la cuenta corriente. Cerrá y reintentá.');
      });

      it('cuenta no habilitada: el Vendedor lee «Pedile al Dueño que se la habilite»', async () => {
        await abrirCC({ cuenta: listo({ habilitada: false }) });

        expect(razonDe(opcionCC())).toBe(
          'Ana Gómez no tiene cuenta corriente habilitada. Pedile al Dueño que se la habilite.',
        );
      });

      it('cuenta no habilitada: al Dueño se le dice dónde habilitarla', async () => {
        await abrirCC({ cuenta: listo({ habilitada: false }), esDueno: true });

        expect(razonDe(opcionCC())).toBe('Ana Gómez no tiene cuenta corriente habilitada. Habilitala desde Clientes.');
      });

      it('el Vendedor con una cuenta que no alcanza: la opción queda deshabilitada y no hay ninguna cifra en el DOM', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_NO_ALCANZA });

        expect(opcionCC().getAttribute('aria-disabled')).toBe('true');
        expect(razonDe(opcionCC())).toBe('No alcanza el límite de la cuenta corriente. Pedile al Dueño.');
        expect(textoDelDialogo()).not.toMatch(/Disponible|Supera|límite \$|saldo \$/);
        expect(textoDelDialogo()).not.toContain('5.000');
      });

      it('las otras formas no llevan razón ni aria-disabled', async () => {
        await abrirCC({ cliente: null });

        for (const nombre of ['Efectivo', 'Débito', 'Crédito', 'Transferencia', MIXTO_OPCION]) {
          expect(medio(nombre).hasAttribute('aria-disabled')).toBe(false);
          expect(medio(nombre).hasAttribute('aria-describedby')).toBe(false);
        }
      });

      it('no hay opción de cuenta corriente: no se agrega ninguna razón (regresión)', async () => {
        await abrir();

        expect(el().querySelector('.razon-medio')).toBeNull();
        expect(el().querySelector('[aria-disabled]')).toBeNull();
      });
    });

    describe('la opción habilitada', () => {
      it('el Vendedor con una cuenta que alcanza: la elige y lee «Alcanza» sin cifras', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });

        opcionCC().click();
        await estabilizar();

        expect(opcionCC().hasAttribute('aria-disabled')).toBe(false);
        expect(elegidos()).toEqual(['Cuenta corriente']);
        expect(lineaCuenta()).toBe('Alcanza');
        expect(textoDelDialogo()).not.toMatch(/Disponible|límite|saldo/);
      });

      it('el Dueño que alcanza ve saldo, límite y disponible (CU-07.4, QA 05/11 C-5)', async () => {
        await abrirCC({ cuenta: DUENO_ALCANZA, esDueno: true });

        opcionCC().click();
        await estabilizar();

        expect(lineaCuenta()).toBe(`Saldo $ 3.000,00 · Límite $ 10.000,00 · Disponible $ 7.000,00`);
      });

      it('el Dueño que no alcanza la puede elegir y ve cuánto se pasa, con el límite y el saldo', async () => {
        await abrirCC({ cuenta: DUENO_NO_ALCANZA, esDueno: true });

        expect(opcionCC().hasAttribute('aria-disabled')).toBe(false);
        opcionCC().click();
        await estabilizar();

        expect(lineaCuenta()).toBe(
          `Supera el límite por $ 1.630,00 (límite $ 5.000,00, saldo $ 3.000,00)`,
        );
      });

      it('cuenta corriente nunca es la selección inicial: F4 + Enter sigue cobrando en efectivo', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });

        expect(elegidos()).toEqual(['Efectivo']);
        tecla('Enter');

        expect(confirmadas).toHaveLength(1);
        expect(confirmadas[0].medio).toBe('EFECTIVO');
      });

      it('sin efectivo y con la cuenta corriente primera pero deshabilitada, abre en la primera forma habilitada', async () => {
        await abrir(363000, [CC, forma(2, 'Débito', 'DEBITO')]);
        fixture.componentRef.setInput('clienteNombre', null);
        await estabilizar();

        expect(elegidos()).toEqual(['Débito']);
      });

      it('con cuenta corriente elegida no hay campo «Recibido» ni recargo', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });

        opcionCC().click();
        await estabilizar();

        expect(el().querySelector('.recibido')).toBeNull();
        expect(el().querySelector('.recargo')).toBeNull();
        expect(el().querySelector('.marca-recargo')).toBeNull();
        expect(normal(el().querySelector('.total')!.textContent)).toBe('$ 3.630,00');
      });

      it('confirma la venta a cuenta corriente sin recibido, sin recargo y sin autorizaExceso', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });
        opcionCC().click();
        await estabilizar();

        botonCobrar().click();

        expect(confirmadas).toEqual([
          { formaPagoId: 5, medio: 'CUENTA_CORRIENTE', recibidoCentavos: null, recargoCentavos: 0 },
        ]);
        expect('autorizaExceso' in confirmadas[0]).toBe(false);
      });

      it('el Enter sobre la cuenta corriente elegida también confirma', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });
        opcionCC().click();
        await estabilizar();

        tecla('Enter', opcionCC());

        expect(confirmadas).toHaveLength(1);
        expect(confirmadas[0].formaPagoId).toBe(5);
      });
    });

    describe('las flechas saltan las opciones deshabilitadas', () => {
      it('→ desde Transferencia pasa de largo la cuenta corriente deshabilitada y va a Mixto', async () => {
        await abrirCC({ cliente: null });
        medio('Transferencia').click();
        await estabilizar();

        tecla('ArrowRight');
        await estabilizar();

        expect(elegidos()).toEqual(['Mixto']);
      });

      it('← desde Mixto pasa de largo la cuenta corriente deshabilitada y va a Transferencia', async () => {
        await abrirCC({ cliente: null });
        medio(MIXTO_OPCION).click();
        await estabilizar();

        tecla('ArrowLeft');
        await estabilizar();

        expect(elegidos()).toEqual(['Transferencia']);
      });

      it('con la cuenta corriente habilitada las flechas sí pasan por ella', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });
        medio('Transferencia').click();
        await estabilizar();

        tecla('ArrowRight');
        await estabilizar();

        expect(elegidos()).toEqual(['Cuenta corriente']);
      });

      it('si la cuenta elegida pasa a no alcanzar (Vendedor), vuelve a la forma inicial', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA });
        opcionCC().click();
        await estabilizar();

        fixture.componentRef.setInput('cuenta', SIN_CIFRAS_NO_ALCANZA);
        await estabilizar();

        expect(elegidos()).toEqual(['Efectivo']);
      });
    });

    describe('autorización del exceso (solo el Dueño)', () => {
      it('el Dueño con una cuenta que no alcanza: el botón principal pasa a «Autorizar exceso y cobrar» y confirma con autorizaExceso', async () => {
        await abrirCC({ cuenta: DUENO_NO_ALCANZA, esDueno: true });
        opcionCC().click();
        await estabilizar();

        expect(normal(botonCobrar().textContent)).toBe('Autorizar exceso y cobrar');
        botonCobrar().click();

        expect(confirmadas).toEqual([
          { formaPagoId: 5, medio: 'CUENTA_CORRIENTE', recibidoCentavos: null, recargoCentavos: 0, autorizaExceso: true },
        ]);
      });

      it('con una cuenta que alcanza el botón sigue siendo «Cobrar»', async () => {
        await abrirCC({ cuenta: DUENO_ALCANZA, esDueno: true });
        opcionCC().click();
        await estabilizar();

        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
      });

      it('con otra forma elegida el botón es «Cobrar» aunque la cuenta no alcance', async () => {
        await abrirCC({ cuenta: DUENO_NO_ALCANZA, esDueno: true });

        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
        botonCobrar().click();
        expect('autorizaExceso' in confirmadas[0]).toBe(false);
      });

      it('el rechazo del servidor por el límite muestra al Dueño el exceso con el límite y el saldo, y le ofrece autorizar', async () => {
        await abrirCC({
          cuenta: SIN_CIFRAS_ALCANZA,
          esDueno: true,
          avisoLimite: { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 3630, exceso: 2630 },
        });
        opcionCC().click();
        await estabilizar();

        expect(lineaCuenta()).toBe(
          `Supera el límite por $ 2.630,00 (límite $ 5.000,00, saldo $ 4.000,00)`,
        );
        expect(normal(botonCobrar().textContent)).toBe('Autorizar exceso y cobrar');
        botonCobrar().click();
        expect(confirmadas[0].autorizaExceso).toBe(true);
      });

      it('el rechazo por el límite del Vendedor (puedeAutorizar false) no muestra cifras ni ofrece autorizar', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA, avisoLimite: { puedeAutorizar: false } });
        opcionCC().click();
        await estabilizar();

        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
        expect(textoDelDialogo()).not.toMatch(/Supera|límite \$|saldo \$/);
      });

      it('un Vendedor nunca manda autorizaExceso aunque llegara un aviso con puedeAutorizar', async () => {
        await abrirCC({
          cuenta: SIN_CIFRAS_ALCANZA,
          esDueno: false,
          avisoLimite: { puedeAutorizar: true, exceso: 10 },
        });
        opcionCC().click();
        await estabilizar();

        botonCobrar().click();

        expect('autorizaExceso' in confirmadas[0]).toBe(false);
        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
      });

      it('si tras el rechazo se elige otra forma, ya no se ofrece autorizar ni se manda el flag', async () => {
        await abrirCC({
          cuenta: SIN_CIFRAS_ALCANZA,
          esDueno: true,
          avisoLimite: { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 3630, exceso: 2630 },
        });
        opcionCC().click();
        await estabilizar();
        medio('Débito').click();
        await estabilizar();

        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
        botonCobrar().click();
        expect(confirmadas[0].medio).toBe('DEBITO');
        expect('autorizaExceso' in confirmadas[0]).toBe(false);
      });
    });

    describe('pago mixto con cuenta corriente', () => {
      it('sin cliente la fila de cuenta corriente está deshabilitada, con la misma razón, y no cuenta para el total', async () => {
        await abrirCC({ cliente: null });
        medio(MIXTO_OPCION).click();
        await estabilizar();

        expect(monto(5).disabled).toBe(true);
        expect(normal(el().querySelector('#razon-fila-5')?.textContent)).toBe(
          'Elegí un cliente (F9) para vender a cuenta corriente.',
        );
        expect(monto(1).disabled).toBe(false);
      });

      it('con la cuenta no habilitada o con error la fila también se deshabilita', async () => {
        await abrirCC({ cuenta: listo({ habilitada: false }) });
        medio(MIXTO_OPCION).click();
        await estabilizar();
        expect(monto(5).disabled).toBe(true);

        fixture.componentRef.setInput('cuenta', { estado: 'error' });
        await estabilizar();
        expect(monto(5).disabled).toBe(true);
        expect(normal(el().querySelector('#razon-fila-5')?.textContent)).toBe(
          'No se pudo verificar la cuenta corriente. Cerrá y reintentá.',
        );
      });

      it('no hay pre-chequeo del importe: con una cuenta que no alcanza la fila queda habilitada y decide el servidor', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_NO_ALCANZA });
        medio(MIXTO_OPCION).click();
        await estabilizar();

        expect(monto(5).disabled).toBe(false);
        expect(el().querySelector('#razon-fila-5')).toBeNull();
      });

      it('una parte a cuenta corriente confirma sus pagos sin autorizaExceso si no hubo rechazo', async () => {
        await abrirCC({ cuenta: SIN_CIFRAS_ALCANZA, total: 10000 });
        medio(MIXTO_OPCION).click();
        await estabilizar();
        for (const [id, texto] of [
          [1, '40'],
          [5, '60'],
        ] as const) {
          monto(id).value = texto;
          monto(id).dispatchEvent(new Event('input', { bubbles: true }));
        }
        fixture.detectChanges();

        botonCobrar().click();

        expect(confirmadas).toEqual([
          {
            formaPagoId: null,
            medio: null,
            recibidoCentavos: null,
            recargoCentavos: 0,
            pagos: [
              { formaPagoId: 1, centavos: 4000 },
              { formaPagoId: 5, centavos: 6000 },
            ],
          },
        ]);
      });

      it('tras un rechazo por el límite, el Dueño autoriza el mixto con una parte a cuenta corriente', async () => {
        await abrirCC({
          cuenta: SIN_CIFRAS_ALCANZA,
          esDueno: true,
          total: 10000,
          avisoLimite: { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 6000, exceso: 5000 },
        });
        medio(MIXTO_OPCION).click();
        await estabilizar();
        for (const [id, texto] of [
          [1, '40'],
          [5, '60'],
        ] as const) {
          monto(id).value = texto;
          monto(id).dispatchEvent(new Event('input', { bubbles: true }));
        }
        fixture.detectChanges();

        expect(lineaCuenta()).toBe(
          `Supera el límite por $ 5.000,00 (límite $ 5.000,00, saldo $ 4.000,00)`,
        );
        expect(normal(botonCobrar().textContent)).toBe('Autorizar exceso y cobrar');
        botonCobrar().click();

        expect(confirmadas[0].autorizaExceso).toBe(true);
        expect(confirmadas[0].pagos).toHaveLength(2);
      });

      it('si la parte a cuenta corriente está vacía, el mixto no pide autorización aunque haya un aviso del límite', async () => {
        await abrirCC({
          cuenta: SIN_CIFRAS_ALCANZA,
          esDueno: true,
          total: 10000,
          avisoLimite: { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 6000, exceso: 5000 },
        });
        medio(MIXTO_OPCION).click();
        await estabilizar();
        monto(1).value = '100';
        monto(1).dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();

        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
        botonCobrar().click();
        expect('autorizaExceso' in confirmadas[0]).toBe(false);
      });

      it('Enter en la fila anterior salta la fila de cuenta corriente deshabilitada en vez de trabarse', async () => {
        await abrir(10000, [forma(1, 'Efectivo', 'EFECTIVO'), CC, forma(3, 'Crédito', 'CREDITO')]);
        fixture.componentRef.setInput('clienteNombre', null);
        medio(MIXTO_OPCION).click();
        await estabilizar();
        monto(1).value = '40';
        monto(1).dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();

        tecla('Enter', monto(1));
        await estabilizar();

        expect(document.activeElement).toBe(monto(3));
        expect(monto(3).value).toBe('60,00');
      });
    });
  });
});
