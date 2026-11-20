import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { ConfirmacionCobro, DialogoCobro } from './dialogo-cobro';

/**
 * movil-sectores T9a (S2, S5, S7): en el celular el cobro de una sola forma de pago (efectivo con recargo y vuelto, y la
 * elección de facturar) es una pantalla completa con «Volver» arriba y la acción fija abajo. Es el mismo componente con el
 * mismo estado y los mismos manejadores: lo que cambia es dónde y cómo se ve. Es el flujo de la plata (item 6): cada prueba
 * de comportamiento corre en el celular y en el escritorio y exige el MISMO resultado. Vitest no lee `styles.css`: el
 * layout se mide en el navegador.
 */

const forma = (
  id: number,
  nombre: string,
  medio: FormaPago['medio'],
  recargoPorcentaje = 0,
  descuentoPorcentaje = 0,
): FormaPago => ({ id, nombre, medio, recargoPorcentaje, descuentoPorcentaje, activa: true, orden: id });

const FORMAS: FormaPago[] = [
  forma(1, 'Efectivo', 'EFECTIVO'),
  forma(2, 'Débito', 'DEBITO'),
  forma(3, 'Crédito', 'CREDITO', 12),
  forma(4, 'Transferencia', 'TRANSFERENCIA', 0, 5),
  forma(5, 'Cuenta corriente', 'CUENTA_CORRIENTE'),
];

/** El celular y el escritorio: cada escenario de dinero tiene que dar lo mismo en los dos. */
const MODOS: [string, string][] = [
  ['celular', '639.98px'],
  ['escritorio', '__ninguna__'],
];

describe('DialogoCobro en el celular (movil-sectores T9a)', () => {
  let fixture: ComponentFixture<DialogoCobro>;
  let confirmadas: ConfirmacionCobro[];
  let canceladas: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"]')!;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => normal(el().querySelector(selector)?.textContent);
  const importe = () => el().querySelector<HTMLInputElement>('.efectivo input')!;
  const medio = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button[role="radio"]')).find((b) =>
      b.textContent!.includes(nombre),
    )!;
  const elegidos = () =>
    Array.from(el().querySelectorAll('button[role="radio"][aria-checked="true"]')).map((b) => normal(b.textContent));
  const facturar = () => el().querySelector<HTMLButtonElement>('button.facturar')!;
  const sinFactura = () => el().querySelector<HTMLButtonElement>('button.sin-factura')!;
  const cobrar = () => el().querySelector<HTMLButtonElement>('button.cobrar')!;
  const volver = () => el().querySelector<HTMLButtonElement>('.ficha-barra .ficha-volver');
  const pie = () => el().querySelector<HTMLElement>('.dialogo.cobro > .dialogo-acciones.ficha-pie');
  const botonesDelPie = () => Array.from(pie()!.querySelectorAll('button')).map((b) => normal(b.textContent));

  function comoCelular(consulta = '639.98px') {
    vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes(consulta), media: c }));
  }

  function tecla(key: string, destino: Element = document.activeElement ?? el()): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function escribir(valor: string) {
    importe().value = valor;
    importe().dispatchEvent(new Event('input', { bubbles: true }));
    await estabilizar();
  }

  async function abrir(
    consulta: string | null,
    cambios: { total?: number; formas?: FormaPago[]; eligeFactura?: boolean; renglones?: number | null } = {},
  ) {
    if (consulta !== null) comoCelular(consulta);
    await TestBed.configureTestingModule({ imports: [DialogoCobro] }).compileComponents();
    fixture = TestBed.createComponent(DialogoCobro);
    confirmadas = [];
    canceladas = 0;
    descartados = 0;
    fixture.componentRef.setInput('totalCentavos', cambios.total ?? 363000);
    fixture.componentRef.setInput('formas', cambios.formas ?? FORMAS);
    fixture.componentRef.setInput('eligeFactura', cambios.eligeFactura ?? false);
    fixture.componentRef.setInput('renglones', cambios.renglones === undefined ? 3 : cambios.renglones);
    fixture.componentInstance.confirmar.subscribe((c) => confirmadas.push(c));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
    await estabilizar();
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  describe('pantalla completa', () => {
    it('es una pantalla completa: la barra con «Volver» y el título, el TOTAL a la vista y el pie fijo al final', async () => {
      await abrir('639.98px');

      expect(dialogo().classList).toContain('cobro-celular');
      expect(dialogo().getAttribute('aria-modal')).toBe('true');
      // El título de la barra es el nombre accesible del diálogo, y no hay dos con el mismo id.
      expect(dialogo().getAttribute('aria-labelledby')).toBe('dialogo-cobro-titulo');
      expect(el().querySelectorAll('#dialogo-cobro-titulo')).toHaveLength(1);
      expect(texto('.ficha-barra h2#dialogo-cobro-titulo')).toBe('Cobrar');
      expect(dialogo().firstElementChild).toBe(el().querySelector('.ficha-barra'));
      expect(volver()!.getAttribute('aria-label')).toBe('Volver');
      expect(volver()!.getAttribute('type')).toBe('button');
      // La cabecera de siempre sigue: el total, los renglones.
      expect(texto('.cabecera .total')).toBe('$ 3.630,00');
      expect(texto('.cabecera .conteo')).toBe('3 renglones');
      // El pie es lo último del diálogo.
      expect(dialogo().lastElementChild).toBe(pie());
    });

    it('sin facturación activa el pie tiene sólo «Cobrar» (con su tecla Enter) y la casilla «No facturable» queda en el cuerpo', async () => {
      await abrir('639.98px');

      expect(botonesDelPie()).toEqual(['Cobrar']);
      expect(cobrar().classList).toContain('primario');
      expect(cobrar().getAttribute('data-tecla')).toBe('Enter');
      expect(cobrar().getAttribute('aria-keyshortcuts')).toBe('Enter');
      expect(pie()!.contains(el().querySelector('.no-facturable'))).toBe(false);
      expect(texto('.no-facturable')).toBe('No facturable cobra igual, pero no se factura nunca');
      expect(texto('.sin-arca')).toBe('Sin factura: la facturación con ARCA no está activa.');
      // «Volver» ya cancela: no hay un «Cancelar» aparte.
      expect(el().querySelector('button.cancelar')).toBeNull();
    });

    it('con facturación activa el pie tiene las dos formas de cobrar, con el texto de hoy, y la pista de elegir con un clic', async () => {
      await abrir('639.98px', { eligeFactura: true });

      expect(botonesDelPie()).toEqual(['Cobrar sin factura', 'Cobrar y facturar con ARCA']);
      expect(facturar().classList).toContain('primario');
      expect(sinFactura().classList).toContain('secundario');
      expect(texto('.pista-factura')).toBe('Elegí con un clic si la venta se factura.');
      // Enter no cobra con la elección a la vista: sin atajo en los botones.
      expect(el().querySelector('[data-tecla="Enter"]')).toBeNull();
      expect(el().querySelector('button.cancelar')).toBeNull();
    });

    it('«Volver» cierra sin cobrar nada, y Esc también', async () => {
      await abrir('639.98px');

      volver()!.click();
      await estabilizar();
      expect(canceladas).toBe(1);

      const evento = tecla('Escape', dialogo());
      expect(canceladas).toBe(2);
      expect(evento.defaultPrevented).toBe(true);
      expect(confirmadas).toEqual([]);
    });

    it('el comprobante y la marca del ambiente siguen en la cabecera', async () => {
      await abrir('639.98px');
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura B · Consumidor Final', ambiente: 'HOMOLOGACIÓN' });
      await estabilizar();

      expect(texto('.cabecera .comprobante')).toBe('Factura B · Consumidor Final HOMOLOGACIÓN');
      expect(texto('.cabecera .ambiente')).toBe('HOMOLOGACIÓN');
    });

    it('el mixto y la cuenta corriente se dibujan como hoy adentro de la misma pantalla (su diseño es de otro slice)', async () => {
      await abrir('639.98px');

      medio('Mixto').click();
      await estabilizar();

      expect(el().querySelector('.mixto')).not.toBeNull();
      expect(el().querySelectorAll('.mixto .fila-pago')).toHaveLength(FORMAS.length);
      expect(volver()).not.toBeNull();
      expect(pie()).not.toBeNull();
    });
  });

  describe.each([
    ['escritorio', '__ninguna__'],
    ['tablet (hasta 1023 px)', '1023.98px'],
    ['sin matchMedia (pruebas, impresión)', null],
  ])('fuera del celular el cobro no cambia: %s', (_nombre, consulta) => {
    it('sigue siendo el diálogo centrado: título en la cabecera, «Cancelar» y «Cobrar» como acciones, sin barra ni pie fijo', async () => {
      await abrir(consulta);

      expect(el().querySelector('.cobro-celular, .ficha-barra, .ficha-volver, .ficha-pie')).toBeNull();
      expect(texto('.cabecera h2#dialogo-cobro-titulo')).toBe('Cobrar');
      expect(dialogo().getAttribute('aria-labelledby')).toBe('dialogo-cobro-titulo');
      expect(Array.from(el().querySelectorAll('.dialogo-acciones button')).map((b) => normal(b.textContent))).toEqual([
        'Cancelar',
        'Cobrar',
      ]);
      el().querySelector<HTMLButtonElement>('button.cancelar')!.click();
      expect(canceladas).toBe(1);
    });

    it('con facturación activa sigue con «Cancelar» y las dos formas de cobrar', async () => {
      await abrir(consulta, { eligeFactura: true });

      expect(el().querySelector('.ficha-barra, .ficha-pie')).toBeNull();
      expect(el().querySelector('button.cancelar')).not.toBeNull();
      expect(facturar().textContent!.trim()).toBe('Cobrar y facturar con ARCA');
      expect(sinFactura().textContent!.trim()).toBe('Cobrar sin factura');
    });
  });

  describe.each([
    ['celular', '639.98px', true],
    ['escritorio', '__ninguna__', false],
    ['tablet', '1023.98px', false],
  ] as const)('orden de lectura y Tab en %s', (_modo, consulta, movil) => {
    // jsdom no navega con Tab: los controles del diálogo usan el orden del DOM y tabindex 0/-1.
    const ordenTab = () => Array.from(dialogo().querySelectorAll<HTMLElement>('button, input'))
      .filter((control) => control.tabIndex >= 0 && !control.matches(':disabled'));
    const casilla = () => el().querySelector('.no-facturable input');
    const antesDe = (primero: string, segundo: string) => {
      const origen = dialogo().querySelector(primero)!;
      const destino = dialogo().querySelector(segundo)!;
      expect(origen.compareDocumentPosition(destino) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    };

    it('mantiene el foco inicial en Recibido y ordena el efectivo, el vuelto y los medios', async () => {
      await abrir(consulta);

      expect(document.activeElement).toBe(importe());
      expect(ordenTab()).toEqual(movil
        ? [volver(), importe(), medio('Efectivo'), casilla(), cobrar()]
        : [medio('Efectivo'), importe(), casilla(), el().querySelector('.cancelar'), cobrar()]);
      if (movil) antesDe('.vuelto', '.medios');
      else antesDe('.medios', '.vuelto');
    });

    it('ordena todos los importes habilitados del mixto respecto de los medios', async () => {
      await abrir(consulta);
      medio('Mixto').click();
      await estabilizar();

      const importes = ['1', '2', '3', '4'].map((id) => dialogo().querySelector(`.mixto input[data-forma="${id}"]`));
      expect(ordenTab()).toEqual(movil
        ? [volver(), ...importes, medio('Mixto'), casilla(), cobrar()]
        : [medio('Mixto'), ...importes, casilla(), el().querySelector('.cancelar'), cobrar()]);
    });

    it('ordena la línea de cuenta corriente respecto de los medios', async () => {
      await abrir(consulta);
      fixture.componentRef.setInput('clienteNombre', 'Ferretería Norte SRL');
      fixture.componentRef.setInput('cuenta', { estado: 'listo', cuenta: { habilitada: true, alcanza: true } });
      await estabilizar();
      medio('Cuenta corriente').click();
      await estabilizar();

      expect(texto('.cuenta-corriente')).toBe('Alcanza');
      if (movil) antesDe('.cuenta-corriente', '.medios');
      else antesDe('.medios', '.cuenta-corriente');
    });
  });

  /**
   * La plata: lo que se manda al servicio, lo que se ve y lo que se rechaza, idéntico en el celular y en el escritorio.
   */
  describe.each(MODOS)('el flujo de la plata es el mismo en el %s', (_modo, consulta) => {
    it('efectivo por el total: recibido igual al total, vuelto 0 a la vista y Enter manda lo de siempre', async () => {
      await abrir(consulta);

      expect(elegidos()).toEqual(['Efectivo']);
      expect(importe().value).toBe('3.630');
      expect(document.activeElement).toBe(importe());
      expect(texto('.vuelto')).toBe('Vuelto $ 0,00');

      tecla('Enter');

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0 },
      ]);
    });

    it('efectivo con más de lo debido: muestra el vuelto y manda el recibido (que no viaja al servidor) sin recargo', async () => {
      await abrir(consulta);

      await escribir('5000');

      expect(texto('.vuelto')).toBe('Vuelto $ 1.370,00');
      expect(el().querySelector('.falta')).toBeNull();
      cobrar().click();

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 500000, recargoCentavos: 0 },
      ]);
    });

    it('efectivo con recargo: el total, el recargo y el recibido llevan el recargo, y el vuelto sale de ese total', async () => {
      await abrir(consulta, { formas: [forma(1, 'Efectivo', 'EFECTIVO', 10), ...FORMAS.slice(1)] });

      expect(texto('.cabecera .total')).toBe('$ 3.993,00');
      expect(texto('.recargo')).toBe('Recargo (10 %) $ 363,00');
      expect(importe().value).toBe('3.993');
      await escribir('4000');
      expect(texto('.vuelto')).toBe('Vuelto $ 7,00');
      cobrar().click();

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 400000, recargoCentavos: 36300 },
      ]);
    });

    it('efectivo con descuento por forma de pago: lo resta del total y lo dice', async () => {
      await abrir(consulta, { formas: [forma(1, 'Efectivo', 'EFECTIVO', 0, 5), ...FORMAS.slice(1)] });

      expect(texto('.cabecera .total')).toBe('$ 3.448,50');
      expect(texto('.descuento-forma')).toBe('Descuento por forma de pago (5 %) −$ 181,50');
      cobrar().click();

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 344850, recargoCentavos: -18150 },
      ]);
    });

    it('si falta plata dice cuánto y no cobra (el botón sigue ahí: no cambia de estado)', async () => {
      await abrir(consulta);

      await escribir('1000');

      expect(texto('.falta')).toBe('Faltan $ 2.630,00');
      expect(el().querySelector('.vuelto')).toBeNull();
      cobrar().click();
      tecla('Enter');

      expect(confirmadas).toEqual([]);
      expect(cobrar().hasAttribute('disabled')).toBe(false);
    });

    it('un recibido vacío o desmedido se rechaza con el mensaje de hoy y no cobra', async () => {
      await abrir(consulta);

      await escribir('');
      expect(texto('[role="alert"]')).toBe('Escribí el importe recibido.');
      expect(importe().getAttribute('aria-invalid')).toBe('true');
      cobrar().click();

      await escribir('2000000');
      expect(texto('[role="alert"]')).toBe('El importe recibido es demasiado alto.');
      cobrar().click();

      expect(confirmadas).toEqual([]);
    });

    it('débito por un clic: sin recibido ni vuelto; crédito con su recargo', async () => {
      await abrir(consulta);

      medio('Débito').click();
      await estabilizar();
      expect(elegidos()).toEqual(['Débito']);
      expect(el().querySelector('.efectivo')).toBeNull();
      cobrar().click();
      expect(confirmadas.pop()).toEqual({ formaPagoId: 2, medio: 'DEBITO', recibidoCentavos: null, recargoCentavos: 0 });

      TestBed.resetTestingModule();
      await abrir(consulta);
      medio('Crédito').click();
      await estabilizar();
      expect(texto('.cabecera .total')).toBe('$ 4.065,60');
      expect(texto('.recargo')).toBe('Recargo (12 %) $ 435,60');
      cobrar().click();
      expect(confirmadas).toEqual([
        { formaPagoId: 3, medio: 'CREDITO', recibidoCentavos: null, recargoCentavos: 43560 },
      ]);
    });

    it('con facturación: «Cobrar y facturar con ARCA» manda la venta para facturar y «Cobrar sin factura», no facturable', async () => {
      await abrir(consulta, { eligeFactura: true });
      await escribir('5000');

      facturar().click();
      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 500000, recargoCentavos: 0 },
      ]);

      TestBed.resetTestingModule();
      await abrir(consulta, { eligeFactura: true });
      await escribir('5000');
      sinFactura().click();
      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 500000, recargoCentavos: 0, noFacturable: true },
      ]);
    });

    it('con facturación Enter no cobra: nadie factura sin querer', async () => {
      await abrir(consulta, { eligeFactura: true });

      tecla('Enter');

      expect(confirmadas).toEqual([]);
    });

    it('con facturación y falta plata ninguno de los dos botones cobra', async () => {
      await abrir(consulta, { eligeFactura: true });
      await escribir('1000');

      facturar().click();
      sinFactura().click();

      expect(texto('.falta')).toBe('Faltan $ 2.630,00');
      expect(confirmadas).toEqual([]);
    });

    it('la casilla «No facturable» marca la venta y arranca destildada', async () => {
      await abrir(consulta);
      const casilla = el().querySelector<HTMLInputElement>('.no-facturable input')!;
      expect(casilla.checked).toBe(false);

      casilla.checked = true;
      casilla.dispatchEvent(new Event('change', { bubbles: true }));
      await estabilizar();
      cobrar().click();

      expect(confirmadas).toEqual([
        { formaPagoId: 1, medio: 'EFECTIVO', recibidoCentavos: 363000, recargoCentavos: 0, noFacturable: true },
      ]);
    });

    it('un segundo toque a «Cobrar» no manda la venta dos veces', async () => {
      await abrir(consulta, { eligeFactura: true });

      facturar().click();
      facturar().click();
      sinFactura().click();

      expect(confirmadas).toHaveLength(1);
    });

    it('mientras viaja la venta no vuelve a mandar y, si se factura, dice que espera a ARCA', async () => {
      await abrir(consulta, { eligeFactura: true });
      fixture.componentRef.setInput('rotuloFactura', { texto: 'Factura B · Consumidor Final', ambiente: null });
      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();

      expect(texto('.aviso')).toBe('Autorizando con ARCA…');
      facturar().click();
      sinFactura().click();

      expect(confirmadas).toEqual([]);
    });

    it('muestra el aviso del contenedor (un precio que cambió) y el tope de Consumidor Final', async () => {
      await abrir(consulta);
      fixture.componentRef.setInput('aviso', 'Cambiaron los precios.');
      fixture.componentRef.setInput('topeConsumidorFinal', 1000);
      await estabilizar();

      expect(Array.from(el().querySelectorAll('.aviso')).map((a) => normal(a.textContent))).toEqual([
        'Cambiaron los precios.',
        'Desde $ 1.000,00 la venta a Consumidor Final necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.',
      ]);
    });

    it('la cuenta corriente sin cliente está apagada con su razón, no se elige y no cobra', async () => {
      await abrir(consulta);
      const cc = medio('Cuenta corriente');

      expect(cc.getAttribute('aria-disabled')).toBe('true');
      expect(cc.getAttribute('aria-describedby')).toBe(`razon-medio-${5}`);
      expect(texto('#razon-medio-5')).toBe('Elegí un cliente para vender a cuenta corriente.');
      cc.click();
      await estabilizar();

      expect(elegidos()).toEqual(['Efectivo']);
      expect(confirmadas).toEqual([]);
    });

    it('el mixto que no suma el total se rechaza con el mensaje de hoy y no manda nada; el que suma manda los pagos', async () => {
      await abrir(consulta);
      medio('Mixto').click();
      await estabilizar();

      cobrar().click();
      await estabilizar();
      expect(texto('.mixto .error')).toBe('Los pagos tienen que sumar el total.');
      expect(confirmadas).toEqual([]);

      const fila = (id: number) => el().querySelector<HTMLInputElement>(`.mixto input[data-forma="${id}"]`)!;
      for (const [id, valor] of [[1, '1000'], [2, '2630']] as const) {
        fila(id).value = valor;
        fila(id).dispatchEvent(new Event('input', { bubbles: true }));
      }
      await estabilizar();
      expect(texto('.resumen-mixto')).toBe('Completo');
      cobrar().click();

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

    it('un escaneo con el cobro abierto se descarta y no cambia lo escrito', async () => {
      await abrir(consulta);

      // Un lector: teclas muy seguidas y un Enter.
      for (const k of '7790001') {
        vi.advanceTimersByTime(5);
        tecla(k);
      }
      vi.advanceTimersByTime(5);
      tecla('Enter');

      expect(confirmadas).toEqual([]);
      expect(descartados).toBe(1);
    });
  });
});
