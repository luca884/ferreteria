import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TipoAjuste } from './ajuste.models';
import { PendienteCuenta } from './cobranza.models';
import { DialogoAjuste, PedidoAjuste } from './dialogo-ajuste';
import { cargarEstilosGlobales } from './estilos-globales.testing';

registerLocaleData(localeEsAr);

const venta = (comprobanteId: number, n: number, importe: number): PendienteCuenta => ({
  comprobanteId,
  ajusteId: null,
  referencia: `Venta N.º ${n}`,
  fecha: '2026-10-10T09:00:00',
  importe,
  imputado: 0,
  pendiente: importe,
});

const recargo = (ajusteId: number, importe: number): PendienteCuenta => ({
  comprobanteId: null,
  ajusteId,
  referencia: `Recargo N.º ${ajusteId}`,
  fecha: '2026-10-11T09:00:00',
  importe,
  imputado: 0,
  pendiente: importe,
});

/** Venta 300 (la más vieja), Recargo 200 y venta 400: el id 3 del Recargo no se pisa con ningún comprobante. */
const A = venta(31, 7, 300);
const R = recargo(3, 200);
const C = venta(33, 9, 400);

describe('DialogoAjuste (GDS-20, G59: Recargo y Descuento de la cuenta del cliente)', () => {
  let fixture: ComponentFixture<DialogoAjuste>;
  let confirmados: PedidoAjuste[];
  let cancelados: number;
  let reiniciados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const importe = () => el().querySelector<HTMLInputElement>('#importe-ajuste')!;
  const motivo = () => el().querySelector<HTMLInputElement>('#motivo-ajuste')!;
  const filaVenta = (id: number) => el().querySelector<HTMLInputElement>(`input[data-comprobante="${id}"]`)!;
  const filaRecargo = (id: number) => el().querySelector<HTMLInputElement>(`input[data-ajuste="${id}"]`)!;
  const filas = () => Array.from(el().querySelectorAll<HTMLInputElement>('.imputacion input'));
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;

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

  async function clic(nombre: string) {
    boton(nombre).click();
    await estabilizar();
  }

  async function abrir(
    tipo: TipoAjuste,
    { pendientes = [A, R, C], saldo = 900, aCuenta = 0 }: { pendientes?: PendienteCuenta[]; saldo?: number; aCuenta?: number } = {},
  ) {
    await TestBed.configureTestingModule({
      imports: [DialogoAjuste],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    cargarEstilosGlobales();
    fixture = TestBed.createComponent(DialogoAjuste);
    confirmados = [];
    cancelados = 0;
    reiniciados = 0;
    fixture.componentRef.setInput('clienteNombre', 'Ana Gómez');
    fixture.componentRef.setInput('tipo', tipo);
    fixture.componentRef.setInput('saldo', saldo);
    fixture.componentRef.setInput('pendientes', pendientes);
    fixture.componentRef.setInput('aCuenta', aCuenta);
    fixture.componentInstance.confirmar.subscribe((p) => confirmados.push(p));
    fixture.componentInstance.cancelar.subscribe(() => cancelados++);
    fixture.componentInstance.reiniciar.subscribe(() => reiniciados++);
    await estabilizar();
  }

  /** Completa importe y motivo y pasa a la confirmación. */
  async function hastaConfirmar(valorImporte: string, valorMotivo = 'Deuda vieja del cliente') {
    await escribir(importe(), valorImporte);
    await escribir(motivo(), valorMotivo);
    await clic('Continuar');
  }

  describe('apertura', () => {
    it('es un diálogo modal con el tipo y el cliente en el título, y el foco en el importe', async () => {
      await abrir('RECARGO');

      const dialogo = el().querySelector('[role="dialog"]')!;
      expect(dialogo.getAttribute('aria-modal')).toBe('true');
      expect(texto('h2')).toBe('Recargo a Ana Gómez');
      expect(document.activeElement).toBe(importe());
    });

    it('el Descuento se titula «Descuento a …»', async () => {
      await abrir('DESCUENTO');

      expect(texto('h2')).toBe('Descuento a Ana Gómez');
    });

    it('un Recargo no imputa: sin tabla, sin «a cuenta» y sin «Imputar en orden»', async () => {
      await abrir('RECARGO');
      await escribir(importe(), '500');

      expect(el().querySelector('table')).toBeNull();
      expect(el().querySelector('.a-cuenta')).toBeNull();
      expect(el().querySelector('#imputar-en-orden')).toBeNull();
    });

    it('el Descuento lista los pendientes FIFO (ventas y Recargos) y arranca vacío, sin precargar la deuda', async () => {
      await abrir('DESCUENTO');

      const renglones = Array.from(el().querySelectorAll('tbody tr')).map((tr) => limpio(tr.textContent));
      expect(renglones).toHaveLength(3);
      expect(renglones[0]).toContain('Venta N.º 7');
      expect(renglones[1]).toContain('Recargo N.º 3');
      expect(renglones[1]).toContain('$ 200,00');
      expect(renglones[2]).toContain('Venta N.º 9');
      expect(importe().value).toBe('');
      expect(filas().map((f) => f.value)).toEqual(['', '', '']);
    });

    it('la referencia de cada pendiente va con la letra del resto de la fila, no la chica del encabezado (#77)', async () => {
      await abrir('DESCUENTO');

      const referencia = el().querySelector<HTMLElement>('tbody th')!;
      const celda = referencia.closest('tr')!.querySelector<HTMLElement>('td')!;
      expect(getComputedStyle(referencia).fontSize).toBe(getComputedStyle(celda).fontSize);
    });

    it('cada campo de imputación sabe de qué partida es: data-comprobante en una venta, data-ajuste en un Recargo', async () => {
      await abrir('DESCUENTO');

      expect(filaVenta(31).getAttribute('aria-label')).toBe('Importe a imputar a Venta N.º 7');
      expect(filaRecargo(3).getAttribute('aria-label')).toBe('Importe a imputar a Recargo N.º 3');
      expect(filaVenta(33)).not.toBeNull();
      expect(el().querySelector('input[data-comprobante="3"]')).toBeNull();
    });

    it('un Descuento sin deudas queda todo a cuenta: sin tabla y con el aviso', async () => {
      await abrir('DESCUENTO', { pendientes: [], saldo: 0 });

      expect(el().querySelector('table')).toBeNull();
      expect(texto('.sin-deudas')).toBe('Sin deudas: el descuento queda a cuenta (saldo a favor)');
    });

    it('si ya tiene saldo a favor lo dice', async () => {
      await abrir('DESCUENTO', { aCuenta: 150 });

      expect(texto('.ya-a-cuenta')).toBe('Ya tiene $ 150,00 a cuenta');
    });
  });

  describe('imputación del Descuento (S2: FIFO sugerido y editable, el resto a cuenta)', () => {
    it('al escribir el importe sugiere FIFO por clave de partida: 300 a la venta, 200 al Recargo, 100 a la otra venta', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '600');

      expect(filaVenta(31).value).toBe('300,00');
      expect(filaRecargo(3).value).toBe('200,00');
      expect(filaVenta(33).value).toBe('100,00');
      expect(el().querySelector('.a-cuenta')).toBeNull();
    });

    it('con el importe 50000 las filas sugeridas y las que se escriben llevan puntos de miles y se mandan como pesos', async () => {
      await abrir('DESCUENTO', { pendientes: [venta(31, 7, 80000)], saldo: 80000 });

      await escribir(importe(), '50000');
      await escribir(motivo(), 'Deuda vieja del cliente');
      expect(importe().value).toBe('50.000');
      expect(filaVenta(31).value).toBe('50.000,00');

      await escribir(filaVenta(31), '20000');
      expect(filaVenta(31).value).toBe('20.000');
      await clic('Continuar');
      await clic('Confirmar descuento');

      expect(confirmados[0].importe).toBe(50000);
      expect(confirmados[0].imputaciones).toEqual([{ comprobanteId: 31, importe: 20000 }]);
    });

    it('lo que sobra de las deudas queda «a cuenta»', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '1000');

      expect(filas().map((f) => f.value)).toEqual(['300,00', '200,00', '400,00']);
      expect(texto('.a-cuenta')).toBe('A cuenta $ 100,00');
    });

    it('editar una fila congela las demás y el resto pasa a «a cuenta»; «Imputar en orden» vuelve a sugerir', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '600');

      await escribir(filaRecargo(3), '50');
      await escribir(importe(), '700');

      expect(filas().map((f) => f.value)).toEqual(['300,00', '50', '100,00']);
      expect(texto('.a-cuenta')).toBe('A cuenta $ 250,00');

      await clic('Imputar en orden');
      expect(filas().map((f) => f.value)).toEqual(['300,00', '200,00', '200,00']);
      expect(el().querySelector('.a-cuenta')).toBeNull();
    });

    it('un importe mayor que lo pendiente de la partida marca el error en la fila y no deja continuar', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '600');
      await escribir(motivo(), 'Condonación acordada');
      await escribir(filaRecargo(3), '250');

      expect(filaRecargo(3).getAttribute('aria-invalid')).toBe('true');
      expect(texto('#error-fila-a3')).toBe('No puede superar lo pendiente ($ 200,00).');
      await clic('Continuar');
      expect(el().querySelector('.efecto-saldo')).toBeNull();
    });

    it('imputar más que el importe del Descuento lo avisa y no deja continuar', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '100');
      await escribir(motivo(), 'Condonación acordada');
      await escribir(filaVenta(31), '300');

      expect(texto('#error-imputado')).toBe('Lo imputado supera el importe.');
      await clic('Continuar');
      expect(el().querySelector('.efecto-saldo')).toBeNull();
    });

    it('si los pendientes cambian (el 409 los refresca) lo editado se descarta y se vuelve a sugerir', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '600');
      await escribir(filaVenta(31), '10');

      fixture.componentRef.setInput('pendientes', [venta(31, 7, 100), R, C]);
      await estabilizar();

      expect(filaVenta(31).value).toBe('100,00');
    });
  });

  describe('validación antes de la confirmación', () => {
    it('sin importe ni motivo no pasa: marca los dos errores y enfoca el primero', async () => {
      await abrir('RECARGO');

      await clic('Continuar');

      expect(texto('#error-importe')).toBe('Escribí el importe del ajuste.');
      expect(texto('#error-motivo')).toBe('Escribí el motivo del ajuste (al menos 5 letras).');
      expect(importe().getAttribute('aria-invalid')).toBe('true');
      expect(motivo().getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(importe());
      expect(el().querySelector('.efecto-saldo')).toBeNull();
    });

    it('el importe lleva la máscara: 50000 se ve 50.000, el punto no entra y los decimales se cortan en 2', async () => {
      await abrir('RECARGO');

      await escribir(importe(), '50000');
      expect(importe().value).toBe('50.000');
      await escribir(importe(), '1.234,567');
      expect(importe().value).toBe('1.234,56');
      expect(texto('#error-importe')).toBeNull();
    });

    it('un importe en cero se marca apenas se escribe', async () => {
      await abrir('RECARGO');

      await escribir(importe(), '0');
      expect(texto('#error-importe')).toBe('El importe tiene que ser mayor que cero.');
    });

    it('un motivo de menos de 5 caracteres no pasa; el contador muestra cuántos van de 200', async () => {
      await abrir('RECARGO');
      await escribir(importe(), '500');
      await escribir(motivo(), 'abc');

      expect(texto('.contador')).toBe('3/200');
      await clic('Continuar');

      expect(texto('#error-motivo')).toBe('Escribí el motivo del ajuste (al menos 5 letras).');
      expect(document.activeElement).toBe(motivo());
      expect(motivo().maxLength).toBe(200);
    });
  });

  describe('confirmación explícita con el efecto en el saldo', () => {
    it('un Recargo pasa por un paso de confirmación: nada se envía hasta confirmar', async () => {
      await abrir('RECARGO', { saldo: 1200 });

      await hastaConfirmar('500');

      expect(confirmados).toEqual([]);
      expect(texto('.resumen')).toContain('Recargo');
      expect(texto('.resumen')).toContain('$ 500,00');
      expect(texto('.resumen')).toContain('Ana Gómez');
      expect(texto('.motivo-resumen')).toBe('Motivo: Deuda vieja del cliente');
      expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 1.200,00 a $ 1.700,00.');
      expect(importe()).toBeNull();
    });

    it('confirmar un Recargo manda tipo, importe en pesos y el motivo recortado, sin imputaciones', async () => {
      await abrir('RECARGO', { saldo: 1200 });
      await hastaConfirmar('1234,50', '  Deuda vieja del cliente  ');

      await clic('Confirmar recargo');

      expect(confirmados).toEqual([{ tipo: 'RECARGO', importe: 1234.5, motivo: 'Deuda vieja del cliente' }]);
      expect('imputaciones' in confirmados[0]).toBe(false);
    });

    it('el foco pasa al botón de confirmar y «Volver» regresa a los datos con lo escrito', async () => {
      await abrir('RECARGO');
      await hastaConfirmar('500');
      expect(document.activeElement).toBe(boton('Confirmar recargo'));

      await clic('Volver');

      expect(importe().value).toBe('500');
      expect(motivo().value).toBe('Deuda vieja del cliente');
      expect(document.activeElement).toBe(importe());
      expect(el().querySelector('.efecto-saldo')).toBeNull();
    });

    it('un Descuento muestra a qué se imputa, lo que queda a cuenta y el saldo que baja', async () => {
      await abrir('DESCUENTO', { saldo: 900 });

      await hastaConfirmar('500');

      const imputado = Array.from(el().querySelectorAll('.imputaciones-resumen li')).map((li) => limpio(li.textContent));
      expect(imputado).toEqual(['Venta N.º 7 $ 300,00', 'Recargo N.º 3 $ 200,00']);
      expect(el().querySelector('.a-cuenta')).toBeNull();
      expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 900,00 a $ 400,00.');
    });

    it('un Descuento mayor que la deuda deja saldo a favor y lo dice con «a favor»', async () => {
      await abrir('DESCUENTO', { saldo: 900 });

      await hastaConfirmar('1000');

      expect(texto('.a-cuenta')).toBe('A cuenta $ 100,00');
      expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 900,00 a $ 100,00 a favor.');
    });

    it('confirmar un Descuento manda cada imputación con comprobanteId (venta) o ajusteId (Recargo), nunca los dos', async () => {
      await abrir('DESCUENTO', { saldo: 900 });
      await hastaConfirmar('600');

      await clic('Confirmar descuento');

      expect(confirmados).toEqual([
        {
          tipo: 'DESCUENTO',
          importe: 600,
          motivo: 'Deuda vieja del cliente',
          imputaciones: [
            { comprobanteId: 31, importe: 300 },
            { ajusteId: 3, importe: 200 },
            { comprobanteId: 33, importe: 100 },
          ],
        },
      ]);
    });

    it('un Descuento sin imputar manda imputaciones vacías: todo queda a cuenta', async () => {
      await abrir('DESCUENTO', { pendientes: [], saldo: 0 });
      await hastaConfirmar('150');

      await clic('Confirmar descuento');

      expect(confirmados).toEqual([{ tipo: 'DESCUENTO', importe: 150, motivo: 'Deuda vieja del cliente', imputaciones: [] }]);
      expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 0,00 a $ 150,00 a favor.');
    });
  });

  describe('teclado', () => {
    it('Esc cierra desde cualquier paso', async () => {
      await abrir('RECARGO');
      const evento = tecla('Escape');
      expect(evento.defaultPrevented).toBe(true);
      expect(cancelados).toBe(1);

      await hastaConfirmar('500');
      tecla('Escape');
      expect(cancelados).toBe(2);
    });

    it('Enter en los datos pasa a la confirmación y Enter en la confirmación envía una sola vez', async () => {
      await abrir('RECARGO');
      await escribir(importe(), '500');
      await escribir(motivo(), 'Deuda vieja del cliente');

      const dialogo = el().querySelector('[role="dialog"]')!;
      tecla('Enter', dialogo);
      await estabilizar();
      expect(texto('.efecto-saldo')).not.toBeNull();
      expect(confirmados).toEqual([]);

      tecla('Enter', dialogo);
      tecla('Enter', dialogo);
      await estabilizar();
      expect(confirmados).toHaveLength(1);
    });

    it('Enter sobre un botón es su clic: sobre «Volver» no confirma el ajuste', async () => {
      await abrir('RECARGO');
      await hastaConfirmar('500');

      tecla('Enter', boton('Volver'));

      expect(confirmados).toEqual([]);
    });

    it('mientras el envío está en vuelo Enter y el botón de confirmar no envían de nuevo', async () => {
      await abrir('RECARGO');
      await hastaConfirmar('500');
      fixture.componentRef.setInput('enVuelo', true);
      await estabilizar();

      tecla('Enter', el().querySelector('[role="dialog"]')!);
      await clic('Confirmar recargo');

      expect(confirmados).toEqual([]);
    });
  });

  describe('respuestas del servidor', () => {
    it('muestra el aviso (deudas que cambiaron) y el error del rechazo', async () => {
      await abrir('DESCUENTO');
      fixture.componentRef.setInput('aviso', 'Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
      fixture.componentRef.setInput('error', 'El cliente está desactivado.');
      await estabilizar();

      expect(texto('.aviso')).toBe('Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
      expect(texto('.error-servidor')).toBe('El cliente está desactivado.');
    });

    it('un 400 por campo se muestra junto al campo y devuelve a los datos para corregir', async () => {
      await abrir('RECARGO');
      await hastaConfirmar('500');

      fixture.componentRef.setInput('errores', { importe: 'El importe es obligatorio.', motivo: 'El motivo es muy corto.' });
      await estabilizar();

      expect(el().querySelector('.efecto-saldo')).toBeNull();
      expect(texto('#error-importe')).toBe('El importe es obligatorio.');
      expect(texto('#error-motivo')).toBe('El motivo es muy corto.');
      expect(importe().getAttribute('aria-invalid')).toBe('true');
    });

    it('una deuda que cambió (nuevos pendientes) devuelve de la confirmación a los datos para revisar la imputación', async () => {
      await abrir('DESCUENTO');
      await hastaConfirmar('500');

      fixture.componentRef.setInput('pendientes', [venta(31, 7, 100), R, C]);
      await estabilizar();

      expect(el().querySelector('.efecto-saldo')).toBeNull();
      expect(importe().value).toBe('500');
      expect(filaVenta(31).value).toBe('100,00');
    });

    it('con el envío sin confirmar (red) el contenido queda fijo y «Cancelar y empezar de nuevo» lo suelta', async () => {
      await abrir('DESCUENTO');
      await hastaConfirmar('500');
      fixture.componentRef.setInput('congelado', true);
      await estabilizar();

      expect(texto('.aviso-congelado')).toBe('No pudimos confirmar el ajuste. Reintentá: se envía lo mismo.');
      expect(boton('Volver').disabled).toBe(true);

      await clic('Cancelar y empezar de nuevo');
      expect(reiniciados).toBe(1);
    });

    it('en los datos con el envío congelado los campos son de solo lectura', async () => {
      await abrir('DESCUENTO');
      await escribir(importe(), '500');
      fixture.componentRef.setInput('congelado', true);
      await estabilizar();

      expect(importe().readOnly).toBe(true);
      expect(motivo().readOnly).toBe(true);
      expect(filas().every((f) => f.readOnly)).toBe(true);
      expect(boton('Imputar en orden').disabled).toBe(true);
    });

    it('la tabla de imputación del Descuento va dentro de .tabla-scroll (S3)', async () => {
      await abrir('DESCUENTO');

      const tabla = el().querySelector('table.imputacion')!;
      const marco = tabla.parentElement!;

      expect(marco.tagName).toBe('DIV');
      expect(marco.classList.contains('tabla-scroll')).toBe(true);
      expect(Array.from(marco.children)).toEqual([tabla]);
    });
  });
});
