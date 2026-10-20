import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PendienteCuenta } from './cobranza.models';
import { DialogoCobranza, PedidoCobranza } from './dialogo-cobranza';
import { cargarEstilosGlobales } from './estilos-globales.testing';

registerLocaleData(localeEsAr);

const pendiente = (comprobanteId: number, n: number, importe: number): PendienteCuenta => ({
  comprobanteId,
  referencia: `Venta N.º ${n}`,
  fecha: '2026-10-10T09:00:00',
  importe,
  imputado: 0,
  pendiente: importe,
});

/** Los pendientes del escenario del spec (CC-032): A 300 (el más viejo), B 500, C 400. */
const A = pendiente(31, 7, 300);
const B = pendiente(32, 8, 500);
const C = pendiente(33, 9, 400);

describe('DialogoCobranza (CC-032, CC-033, CC-034, CC-043, CC-051)', () => {
  let fixture: ComponentFixture<DialogoCobranza>;
  let confirmadas: PedidoCobranza[];
  let canceladas: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const total = () => el().querySelector<HTMLInputElement>('#total-cobranza')!;
  const fila = (id: number) => el().querySelector<HTMLInputElement>(`input[data-comprobante="${id}"]`)!;
  const filas = () => Array.from(el().querySelectorAll<HTMLInputElement>('input[data-comprobante]'));
  const medio = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button[role="radio"]')).find((b) => b.textContent!.includes(nombre))!;
  const elegidos = () =>
    Array.from(el().querySelectorAll('button[role="radio"][aria-checked="true"]')).map((b) => limpio(b.textContent));
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;
  const aCuenta = () => limpio(el().querySelector('.a-cuenta')?.textContent) || null;
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;

  function tecla(key: string, destino: Element = document.activeElement ?? el()): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function escribir(campo: HTMLInputElement, valor: string) {
    campo.value = valor;
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    await estabilizar();
  }

  const escribirTotal = (valor: string) => escribir(total(), valor);
  const escribirFila = (id: number, valor: string) => escribir(fila(id), valor);

  async function abrir(pendientes: PendienteCuenta[] = [A, B, C], aCuentaActual = 0) {
    await TestBed.configureTestingModule({
      imports: [DialogoCobranza],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    cargarEstilosGlobales();
    fixture = TestBed.createComponent(DialogoCobranza);
    confirmadas = [];
    canceladas = 0;
    fixture.componentRef.setInput('clienteNombre', 'Ana Gómez');
    fixture.componentRef.setInput('pendientes', pendientes);
    fixture.componentRef.setInput('aCuenta', aCuentaActual);
    fixture.componentInstance.confirmar.subscribe((c) => confirmadas.push(c));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    await estabilizar();
  }

  describe('apertura', () => {
    it('es un diálogo modal con el nombre del cliente y el foco en el total', async () => {
      await abrir();

      const dialogo = el().querySelector('[role="dialog"]')!;
      expect(dialogo.getAttribute('aria-modal')).toBe('true');
      expect(limpio(dialogo.querySelector('h2')!.textContent)).toBe('Cobrar a Ana Gómez');
      expect(document.activeElement).toBe(total());
    });

    it('arranca con el total vacío y no precarga la deuda: ningún importe sugerido todavía', async () => {
      await abrir();

      expect(total().value).toBe('');
      expect(filas().map((f) => f.value)).toEqual(['', '', '']);
      expect(aCuenta()).toBeNull();
    });

    it('Efectivo es el medio por defecto', async () => {
      await abrir();

      expect(elegidos()).toEqual(['Efectivo']);
      expect(Array.from(el().querySelectorAll('button[role="radio"]')).map((b) => limpio(b.textContent))).toEqual([
        'Efectivo',
        'Transferencia',
      ]);
    });

    it('lista los pendientes en orden FIFO con su referencia y lo que falta cobrar', async () => {
      await abrir();

      const renglones = Array.from(el().querySelectorAll('tbody tr')).map((tr) => limpio(tr.textContent));
      expect(renglones).toHaveLength(3);
      expect(renglones[0]).toContain('Venta N.º 7');
      expect(renglones[0]).toContain(`$ 300,00`);
      expect(renglones[1]).toContain('Venta N.º 8');
      expect(renglones[2]).toContain('Venta N.º 9');
    });

    it('la referencia de cada fila no se fuerza a mayúsculas («Venta N.º 7», no «VENTA N.º 7»)', async () => {
      await abrir();

      const referencia = el().querySelector<HTMLElement>('tbody th')!;
      expect(referencia.textContent).toContain('Venta N.º 7');
      expect(getComputedStyle(referencia).textTransform).toBe('none');
    });

    it('la referencia de cada fila es un dato más: sin el fondo gris ni el sticky del encabezado de columna', async () => {
      await abrir();

      const referencia = getComputedStyle(el().querySelector<HTMLElement>('tbody th')!);
      expect(referencia.position).toBe('static');
      expect(['transparent', 'rgba(0, 0, 0, 0)']).toContain(referencia.backgroundColor);
      const encabezado = getComputedStyle(el().querySelector<HTMLElement>('thead th')!);
      expect(encabezado.position).toBe('sticky');
    });

    it('las celdas de una fila (referencia, pendiente e importe) se alinean al centro, junto al campo', async () => {
      await abrir();

      const celdas = Array.from(el().querySelectorAll<HTMLElement>('tbody tr:first-child > *'));
      expect(celdas).toHaveLength(3);
      expect(celdas.map((c) => getComputedStyle(c).verticalAlign)).toEqual(['middle', 'middle', 'middle']);
    });

    it('cada fila tiene su importe como campo con nombre accesible', async () => {
      await abrir();

      expect(fila(31).getAttribute('aria-label')).toBe('Importe a imputar a Venta N.º 7');
    });
  });

  describe('teclado', () => {
    it('con el foco en el medio, ← y → lo cambian (con preventDefault), ciclan y el foco acompaña a la selección', async () => {
      await abrir();
      medio('Efectivo').focus();

      const derecha = tecla('ArrowRight');
      await estabilizar();
      expect(elegidos()).toEqual(['Transferencia']);
      expect(derecha.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(medio('Transferencia'));

      tecla('ArrowRight');
      await estabilizar();
      expect(elegidos()).toEqual(['Efectivo']);
      expect(document.activeElement).toBe(medio('Efectivo'));

      tecla('ArrowLeft');
      await estabilizar();
      expect(elegidos()).toEqual(['Transferencia']);
    });

    it('con el foco en el medio, ↑ y ↓ también lo cambian', async () => {
      await abrir();
      medio('Efectivo').focus();

      tecla('ArrowDown');
      await estabilizar();
      expect(elegidos()).toEqual(['Transferencia']);

      tecla('ArrowUp');
      await estabilizar();
      expect(elegidos()).toEqual(['Efectivo']);
    });

    it('dentro del total las flechas quedan para el cursor: no cambian el medio ni se cancelan', async () => {
      await abrir();
      await escribirTotal('600');

      for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
        expect(tecla(key, total()).defaultPrevented).toBe(false);
      }
      await estabilizar();

      expect(elegidos()).toEqual(['Efectivo']);
    });

    it('dentro de un importe de fila las flechas tampoco cambian el medio', async () => {
      await abrir();

      const evento = tecla('ArrowRight', fila(31));
      await estabilizar();

      expect(evento.defaultPrevented).toBe(false);
      expect(elegidos()).toEqual(['Efectivo']);
    });

    it('el medio es un grupo de radios con nombre y con la pista visible como descripción (sin aria-hidden)', async () => {
      await abrir();

      const grupo = el().querySelector('[role="radiogroup"]')!;
      expect(grupo.getAttribute('aria-label')).toBe('Medio de cobro');
      const pista = el().querySelector(`#${grupo.getAttribute('aria-describedby')}`)!;
      expect(limpio(pista.textContent)).toContain('cambian el medio');
      expect(pista.closest('[aria-hidden]')).toBeNull();
    });

    it('roving tabindex: el radio elegido es la parada de Tab (0) y el otro queda en -1', async () => {
      await abrir();
      const tabindex = () => ['Efectivo', 'Transferencia'].map((m) => medio(m).getAttribute('tabindex'));
      expect(tabindex()).toEqual(['0', '-1']);

      medio('Transferencia').click();
      await estabilizar();

      expect(tabindex()).toEqual(['-1', '0']);
    });

    it('Tab recorre el total, el medio, las filas y los botones', async () => {
      await abrir();

      const orden = Array.from(
        el().querySelectorAll<HTMLElement>('input, button, [tabindex]:not([tabindex="-1"])'),
      )
        .filter((e) => e.getAttribute('tabindex') !== '-1' && !(e as HTMLButtonElement).disabled)
        .map((e) => e.id || e.dataset['comprobante'] || e.className.split(' ')[0]);

      expect(orden).toEqual(['total-cobranza', 'medio', '31', '32', '33', 'imputar-en-orden', 'cancelar', 'cobrar']);
    });

    it('un clic en el fondo no le saca el foco al diálogo (se cancela el mousedown del fondo, no el de los controles)', async () => {
      await abrir();

      const sobreFondo = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      el().querySelector('.dialogo-fondo')!.dispatchEvent(sobreFondo);
      const sobreCampo = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      total().dispatchEvent(sobreCampo);

      expect(sobreFondo.defaultPrevented).toBe(true);
      expect(sobreCampo.defaultPrevented).toBe(false);
    });

    it('un clic en una zona sin controles deja el foco en el diálogo, y Esc y Enter siguen andando', async () => {
      await abrir();
      await escribirTotal('600');
      const dialogo = el().querySelector<HTMLElement>('[role="dialog"]')!;
      expect(dialogo.getAttribute('tabindex')).toBe('-1');

      dialogo.focus();
      expect(document.activeElement).toBe(dialogo);
      tecla('Enter');
      tecla('Escape');

      expect(confirmadas).toHaveLength(1);
      expect(canceladas).toBe(1);
    });

    it('Esc cierra (emite cancelar) sin confirmar nada', async () => {
      await abrir();

      const esc = tecla('Escape');

      expect(canceladas).toBe(1);
      expect(confirmadas).toEqual([]);
      expect(esc.defaultPrevented).toBe(true);
    });

    it('Enter confirma si todo es válido', async () => {
      await abrir();
      await escribirTotal('600');

      tecla('Enter');

      expect(confirmadas).toEqual([
        {
          medio: 'EFECTIVO',
          total: 600,
          imputaciones: [
            { comprobanteId: 31, importe: 300 },
            { comprobanteId: 32, importe: 300 },
          ],
        },
      ]);
    });

    it('Enter con el total vacío no confirma: enfoca el total y dice qué falta', async () => {
      await abrir();
      (fila(31) as HTMLElement).focus();

      tecla('Enter');
      await estabilizar();

      expect(confirmadas).toEqual([]);
      expect(document.activeElement).toBe(total());
      expect(total().getAttribute('aria-invalid')).toBe('true');
      expect(texto('#error-total')).toBe('Escribí el total a cobrar.');
    });

    it('Enter con un importe de fila inválido enfoca esa fila (el primer campo con error)', async () => {
      await abrir();
      await escribirTotal('600');
      await escribirFila(32, '999');

      tecla('Enter');
      await estabilizar();

      expect(confirmadas).toEqual([]);
      expect(document.activeElement).toBe(fila(32));
    });

    it('Enter sobre un botón no confirma el cobro: lo resuelve el botón (Cancelar cierra, «Imputar en orden» reaplica)', async () => {
      await abrir();
      await escribirTotal('600');
      const imputar = boton('Imputar en orden');
      imputar.focus();

      tecla('Enter', imputar);

      expect(confirmadas).toEqual([]);
    });
  });

  describe('imputación FIFO', () => {
    it('al tipear el total propone A 300 + B 300 (CC-043)', async () => {
      await abrir();

      await escribirTotal('600');

      expect(filas().map((f) => f.value)).toEqual(['300,00', '300,00', '']);
      expect(aCuenta()).toBeNull();
    });

    it('se re-sugiere al cambiar el total mientras no se editó a mano', async () => {
      await abrir();
      await escribirTotal('600');

      await escribirTotal('1200');
      expect(filas().map((f) => f.value)).toEqual(['300,00', '500,00', '400,00']);

      await escribirTotal('100');
      expect(filas().map((f) => f.value)).toEqual(['100,00', '', '']);
    });

    it('editada a mano (B 500 + C 100) conserva lo editado aunque cambie el total, y confirma eso y no lo sugerido (CC-032)', async () => {
      await abrir();
      await escribirTotal('600');

      await escribirFila(31, '');
      await escribirFila(32, '500');
      await escribirFila(33, '100');
      expect(filas().map((f) => f.value)).toEqual(['', '500', '100']);

      await escribirTotal('600,00');
      expect(filas().map((f) => f.value)).toEqual(['', '500', '100']);

      tecla('Enter');
      expect(confirmadas).toEqual([
        {
          medio: 'EFECTIVO',
          total: 600,
          imputaciones: [
            { comprobanteId: 32, importe: 500 },
            { comprobanteId: 33, importe: 100 },
          ],
        },
      ]);
    });

    it('«Imputar en orden» descarta lo editado y reaplica la sugerencia FIFO', async () => {
      await abrir();
      await escribirTotal('600');
      await escribirFila(31, '0');
      await escribirFila(33, '300');

      boton('Imputar en orden').click();
      await estabilizar();

      expect(filas().map((f) => f.value)).toEqual(['300,00', '300,00', '']);
    });

    it('«A cuenta $X» aparece cuando lo imputado no llega al total, y se va cuando lo imputado lo iguala', async () => {
      await abrir();

      await escribirTotal('1500');
      expect(aCuenta()).toBe(`A cuenta $ 300,00`);

      await escribirTotal('1200');
      expect(aCuenta()).toBeNull();
    });

    it('desmarcar un comprobante (importe vacío) manda el resto a cuenta', async () => {
      await abrir();
      await escribirTotal('600');

      await escribirFila(32, '');

      expect(aCuenta()).toBe(`A cuenta $ 300,00`);
      tecla('Enter');
      expect(confirmadas[0].imputaciones).toEqual([{ comprobanteId: 31, importe: 300 }]);
    });

    it('un cobro sin imputar nada (todos los importes vacíos) queda todo a cuenta y se puede confirmar', async () => {
      await abrir();
      await escribirTotal('400');
      await escribirFila(31, '');

      expect(aCuenta()).toBe(`A cuenta $ 300,00`);
      await escribirFila(32, '');
      expect(aCuenta()).toBe(`A cuenta $ 400,00`);

      tecla('Enter');
      expect(confirmadas).toEqual([{ medio: 'EFECTIVO', total: 400, imputaciones: [] }]);
    });

    it('con los pendientes que cambian (409) vuelve a sugerir y descarta lo editado', async () => {
      await abrir();
      await escribirTotal('600');
      await escribirFila(33, '100');

      fixture.componentRef.setInput('pendientes', [{ ...A, pendiente: 0, imputado: 300 }, B, C].filter((p) => p.pendiente > 0));
      await estabilizar();

      expect(filas().map((f) => f.value)).toEqual(['500,00', '100,00']);
    });

    it('centavos: A 0,01 y B 0,99 con 1,00 proponen 0,01 y 0,99 sin perder ni inventar un centavo', async () => {
      await abrir([pendiente(1, 1, 0.01), pendiente(2, 2, 0.99)]);

      await escribirTotal('1');

      expect(filas().map((f) => f.value)).toEqual(['0,01', '0,99']);
      tecla('Enter');
      expect(confirmadas[0].imputaciones).toEqual([
        { comprobanteId: 1, importe: 0.01 },
        { comprobanteId: 2, importe: 0.99 },
      ]);
    });

    it('acepta el punto o la coma como separador decimal', async () => {
      await abrir();

      await escribirTotal('100.5');
      expect(filas()[0].value).toBe('100,50');

      await escribirTotal('100,5');
      expect(filas()[0].value).toBe('100,50');
    });
  });

  describe('validaciones en línea', () => {
    it('total 0 es inválido: error enlazado con aria-invalid y aria-describedby, y no confirma', async () => {
      await abrir();

      await escribirTotal('0');
      tecla('Enter');
      await estabilizar();

      expect(confirmadas).toEqual([]);
      expect(total().getAttribute('aria-invalid')).toBe('true');
      expect(total().getAttribute('aria-describedby')).toBe('error-total');
      expect(texto('#error-total')).toBe('El total tiene que ser mayor que cero.');
    });

    it('un total con más de 2 decimales o que no es un número es inválido', async () => {
      await abrir();

      await escribirTotal('10,123');
      expect(texto('#error-total')).toBe('Usá un número con hasta 2 decimales.');

      await escribirTotal('abc');
      expect(texto('#error-total')).toBe('Usá un número con hasta 2 decimales.');
      expect(total().getAttribute('aria-invalid')).toBe('true');
    });

    it('un total más grande que el máximo es inválido (el servidor lo rechazaría)', async () => {
      await abrir();

      await escribirTotal('10000000000000');

      expect(texto('#error-total')).toBe('El total es demasiado grande.');
    });

    it('un total válido limpia el error y los atributos', async () => {
      await abrir();
      await escribirTotal('0');

      await escribirTotal('50');

      expect(el().querySelector('#error-total')).toBeNull();
      expect(total().getAttribute('aria-invalid')).toBeNull();
      expect(total().getAttribute('aria-describedby')).toBeNull();
    });

    it('un importe mayor que lo pendiente marca esa fila: no puede superar el pendiente', async () => {
      await abrir();
      await escribirTotal('1000');

      await escribirFila(31, '400');

      expect(fila(31).getAttribute('aria-invalid')).toBe('true');
      expect(fila(31).getAttribute('aria-describedby')).toBe('error-fila-31');
      expect(texto('#error-fila-31')).toBe(`No puede superar lo pendiente ($ 300,00).`);
      expect(fila(32).getAttribute('aria-invalid')).toBeNull();
    });

    it('un importe de fila con más de 2 decimales o que no es un número es inválido', async () => {
      await abrir();
      await escribirTotal('1000');

      await escribirFila(31, '10,555');
      expect(texto('#error-fila-31')).toBe('Usá un número con hasta 2 decimales.');

      await escribirFila(31, 'x');
      expect(texto('#error-fila-31')).toBe('Usá un número con hasta 2 decimales.');
    });

    it('lo imputado no puede superar el total: error del conjunto y no confirma', async () => {
      await abrir();
      await escribirTotal('100');

      await escribirFila(31, '60');
      await escribirFila(32, '60');
      tecla('Enter');
      await estabilizar();

      expect(texto('#error-imputado')).toBe('Lo imputado supera el total.');
      expect(confirmadas).toEqual([]);
    });

    it('lo imputado 1 centavo por encima del total también es un error', async () => {
      await abrir();
      await escribirTotal('100');

      await escribirFila(31, '50,01');
      await escribirFila(32, '50');

      expect(texto('#error-imputado')).toBe('Lo imputado supera el total.');
    });

    it('1 centavo de más sobre lo pendiente se detecta (sin errores de coma flotante)', async () => {
      await abrir([pendiente(1, 1, 100.1)]);
      await escribirTotal('200');

      await escribirFila(1, '100,11');
      expect(fila(1).getAttribute('aria-invalid')).toBe('true');

      await escribirFila(1, '100,1');
      expect(fila(1).getAttribute('aria-invalid')).toBeNull();
    });

    it('con un error el botón Cobrar no emite nada', async () => {
      await abrir();
      await escribirTotal('0');

      boton('Cobrar').click();

      expect(confirmadas).toEqual([]);
    });
  });

  describe('medio', () => {
    it('confirma con transferencia si se eligió', async () => {
      await abrir();
      await escribirTotal('300');
      medio('Transferencia').click();
      await estabilizar();

      tecla('Enter');

      expect(confirmadas[0].medio).toBe('TRANSFERENCIA');
    });
  });

  describe('cliente sin deuda: adelanto (CC-034)', () => {
    it('no hay tabla, el diálogo se abre igual y avisa que el cobro queda a cuenta', async () => {
      await abrir([]);

      expect(el().querySelector('table')).toBeNull();
      expect(el().querySelector('button[role="radio"]')).not.toBeNull();
      expect(texto('.sin-deudas')).toBe('Sin deudas: el cobro queda a cuenta (adelanto)');
      expect(el().querySelector('#imputar-en-orden')).toBeNull();
      expect(document.activeElement).toBe(total());
    });

    it('confirma con imputaciones vacías y el total íntegro', async () => {
      await abrir([]);
      await escribirTotal('500');

      tecla('Enter');

      expect(confirmadas).toEqual([{ medio: 'EFECTIVO', total: 500, imputaciones: [] }]);
    });

    it('un adelanto con el total vacío o en 0 no confirma', async () => {
      await abrir([]);

      tecla('Enter');
      await escribirTotal('0');
      tecla('Enter');

      expect(confirmadas).toEqual([]);
    });

    it('muestra «A cuenta $X» con el total tipeado', async () => {
      await abrir([]);

      await escribirTotal('500');

      expect(aCuenta()).toBe(`A cuenta $ 500,00`);
    });

    it('el aviso de adelanto no aparece cuando hay pendientes', async () => {
      await abrir();

      expect(el().querySelector('.sin-deudas')).toBeNull();
    });
  });

  describe('saldo a favor', () => {
    it('con pendientes y algo ya a cuenta muestra lo que el cliente tiene a cuenta', async () => {
      await abrir([A, B, C], 250);

      expect(texto('.ya-a-cuenta')).toBe(`Ya tiene $ 250,00 a cuenta`);
    });

    it('sin nada a cuenta no muestra esa línea', async () => {
      await abrir([A], 0);

      expect(el().querySelector('.ya-a-cuenta')).toBeNull();
    });
  });

  describe('mientras el servidor responde', () => {
    it('confirmar emite una sola vez aunque se apriete dos veces; al terminar la llamada vuelve a poder confirmar', async () => {
      await abrir();
      await escribirTotal('300');

      tecla('Enter');
      tecla('Enter');
      boton('Cobrar').click();
      expect(confirmadas).toHaveLength(1);

      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();
      fixture.componentRef.setInput('enVuelo', false);
      await estabilizar();

      tecla('Enter');
      expect(confirmadas).toHaveLength(2);
    });

    it('el contenedor puede mostrar un aviso (la deuda cambió) y un error', async () => {
      await abrir();

      fixture.componentRef.setInput('aviso', 'Las deudas cambiaron: revisá la imputación.');
      fixture.componentRef.setInput('error', 'La caja está cerrada.');
      await estabilizar();

      expect(texto('.aviso')).toBe('Las deudas cambiaron: revisá la imputación.');
      expect(texto('.error-servidor')).toBe('La caja está cerrada.');
      expect(el().querySelector('.error-servidor')!.getAttribute('role')).toBe('alert');
    });
  });

  describe('envío sin confirmar: el contenido queda congelado hasta reintentar o empezar de nuevo (CC-038)', () => {
    let reiniciadas: number;

    /** Abre con A 300 + B 500 + C 400, tipea 600 (→ A 300 + B 300) y confirma; después el contenedor congela. */
    async function congelar() {
      await abrir();
      reiniciadas = 0;
      fixture.componentInstance.reiniciar.subscribe(() => reiniciadas++);
      await escribirTotal('600');
      tecla('Enter');
      fixture.componentRef.setInput('congelado', true);
      await estabilizar();
    }

    it('sin envío pendiente no hay aviso ni salida, y todo se puede editar', async () => {
      await abrir();

      expect(el().querySelector('.aviso-congelado')).toBeNull();
      expect(boton('Cancelar y empezar de nuevo')).toBeUndefined();
      expect(total().readOnly).toBe(false);
      expect(filas().every((f) => !f.readOnly)).toBe(true);
    });

    it('avisa que se reenvía lo mismo y ofrece «Cancelar y empezar de nuevo»', async () => {
      await congelar();

      expect(texto('.aviso-congelado')).toBe('No pudimos confirmar el cobro. Reintentá: se envía lo mismo.');
      expect(el().querySelector('.aviso-congelado')!.getAttribute('role')).toBe('status');
      expect(boton('Cancelar y empezar de nuevo')).toBeDefined();
    });

    it('el total y los importes de las filas quedan de solo lectura (sin perder el foco)', async () => {
      await congelar();

      expect(total().readOnly).toBe(true);
      expect(filas().every((f) => f.readOnly)).toBe(true);
      expect(document.activeElement).toBe(total());
    });

    it('el medio queda deshabilitado (aria-disabled) y «Imputar en orden» también', async () => {
      await congelar();

      const radios = Array.from(el().querySelectorAll('button[role="radio"]'));
      expect(radios.every((r) => r.getAttribute('aria-disabled') === 'true')).toBe(true);
      expect(boton('Imputar en orden').disabled).toBe(true);
    });

    it('tipear en el total o en una fila no cambia lo que se reenvía', async () => {
      await congelar();

      await escribirTotal('50');
      await escribirFila(31, '1');
      tecla('Enter');

      expect(confirmadas).toHaveLength(2);
      expect(confirmadas[1]).toEqual(confirmadas[0]);
    });

    it('elegir otro medio (clic o flechas) no lo cambia', async () => {
      await congelar();

      medio('Transferencia').click();
      medio('Efectivo').focus();
      tecla('ArrowRight');
      await estabilizar();

      expect(elegidos()).toEqual(['Efectivo']);
    });

    it('«Imputar en orden» no reaplica nada mientras está congelado', async () => {
      await abrir();
      await escribirTotal('600');
      await escribirFila(31, '100');
      fixture.componentRef.setInput('congelado', true);
      await estabilizar();

      fixture.componentInstance['imputarEnOrden']();
      await estabilizar();

      expect(fila(31).value).toBe('100');
    });

    it('Enter (y «Cobrar») reintentan con exactamente el mismo contenido', async () => {
      await congelar();

      tecla('Enter');
      boton('Cobrar').click();
      await estabilizar();

      expect(confirmadas).toHaveLength(2);
      expect(confirmadas[1]).toEqual(confirmadas[0]);
    });

    it('Esc sigue cerrando el diálogo', async () => {
      await congelar();

      tecla('Escape');

      expect(canceladas).toBe(1);
    });

    it('«Cancelar y empezar de nuevo» emite reiniciar (y no cancelar ni confirmar)', async () => {
      await congelar();

      boton('Cancelar y empezar de nuevo').click();

      expect(reiniciadas).toBe(1);
      expect(canceladas).toBe(0);
      expect(confirmadas).toHaveLength(1);
    });

    it('Enter sobre «Cancelar y empezar de nuevo» no reenvía el cobro', async () => {
      await congelar();

      const reiniciar = boton('Cancelar y empezar de nuevo');
      reiniciar.focus();
      tecla('Enter', reiniciar);

      expect(confirmadas).toHaveLength(1);
    });

    it('al liberar el contenido vuelve a ser editable', async () => {
      await congelar();

      fixture.componentRef.setInput('congelado', false);
      await estabilizar();
      await escribirTotal('500');

      expect(el().querySelector('.aviso-congelado')).toBeNull();
      expect(total().readOnly).toBe(false);
      expect(filas().every((f) => !f.readOnly)).toBe(true);
      expect(elegidos()).toEqual(['Efectivo']);
      expect(fila(31).value).toBe('300,00');
      expect(fila(32).value).toBe('200,00');
    });

    it('al liberar, el medio se puede cambiar de nuevo', async () => {
      await congelar();
      fixture.componentRef.setInput('congelado', false);
      await estabilizar();

      medio('Transferencia').click();
      await estabilizar();

      expect(elegidos()).toEqual(['Transferencia']);
      expect(medio('Transferencia').getAttribute('aria-disabled')).not.toBe('true');
    });
  });
});
