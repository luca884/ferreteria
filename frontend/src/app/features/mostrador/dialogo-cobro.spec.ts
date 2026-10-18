import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { DialogoCobro, ConfirmacionCobro } from './dialogo-cobro';

const NBSP = ' ';

const forma = (id: number, nombre: string, medio: FormaPago['medio'], recargoPorcentaje = 0): FormaPago => ({
  id,
  nombre,
  medio,
  recargoPorcentaje,
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
    expect(importe().value).toBe('3630,00');
    expect(document.activeElement).toBe(importe());
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

  describe('no facturable (NF-003)', () => {
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
    expect(importe().value).toBe('3650,00');
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

    it('«Mixto» muestra una fila por forma, sin el recibido, con el foco en efectivo y todo por cobrar', async () => {
      await abrirMixto();

      expect(elegidos()).toEqual(['Mixto']);
      expect(el().querySelectorAll('input[data-forma]')).toHaveLength(4);
      expect(el().querySelector('.recibido')).toBeNull();
      expect(document.activeElement).toBe(monto(1));
      expect(resumen()).toBe('Falta $ 3.630,00');
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
      expect(monto(2).value).toBe('2630,00');
      expect(resumen()).toBe('Completo');
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

    it('un importe que no es número avisa y no confirma', async () => {
      await abrirMixto();
      escribirMonto(1, 'abc');

      tecla('Enter');
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(error()).toBe('Revisá los importes: usá números con hasta 2 decimales.');
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

      expect(importe().value).toBe('105,00');
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
});
