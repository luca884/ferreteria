import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Recibo } from './cobranza.models';
import { DialogoAnulacion, HOY } from './dialogo-anulacion';
import { cargarEstilosGlobales } from './estilos-globales.testing';

registerLocaleData(localeEsAr);

describe('DialogoAnulacion (CC-056, CC-097)', () => {
  const recibo = (cambios: Partial<Recibo> = {}): Recibo => ({
    id: 5,
    numero: 12,
    fecha: '2026-10-19T10:30:00',
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    medio: 'EFECTIVO',
    total: 700,
    aCuenta: 0,
    cobradoPor: 'Dueño Tornillo',
    imputaciones: [
      { comprobanteId: 31, referencia: 'Venta N.º 7', importe: 300 },
      { comprobanteId: 32, referencia: 'Venta N.º 8', importe: 400 },
    ],
    estado: 'EMITIDO',
    fechaAnulacion: null,
    anuladoPor: null,
    motivoAnulacion: null,
    ...cambios,
  });

  let fixture: ComponentFixture<DialogoAnulacion>;
  let confirmadas: string[];
  let canceladas: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const motivo = () => el().querySelector<HTMLInputElement>('#motivo-anulacion')!;
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

  async function escribir(valor: string) {
    motivo().value = valor;
    motivo().dispatchEvent(new Event('input', { bubbles: true }));
    await estabilizar();
  }

  async function abrir(r: Recibo = recibo(), extra: { enVuelo?: boolean; error?: string | null; hoy?: string } = {}) {
    cargarEstilosGlobales();
    await TestBed.configureTestingModule({
      imports: [DialogoAnulacion],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: HOY, useValue: () => extra.hoy ?? '2026-10-19' },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DialogoAnulacion);
    confirmadas = [];
    canceladas = 0;
    fixture.componentRef.setInput('recibo', r);
    if (extra.enVuelo !== undefined) fixture.componentRef.setInput('enVuelo', extra.enVuelo);
    if (extra.error !== undefined) fixture.componentRef.setInput('error', extra.error);
    fixture.componentInstance.confirmar.subscribe((m) => confirmadas.push(m));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    await estabilizar();
  }

  describe('contenido', () => {
    it('es un diálogo modal titulado con el número del recibo', async () => {
      await abrir();

      const dialogo = el().querySelector('[role="dialog"]')!;
      expect(dialogo.getAttribute('aria-modal')).toBe('true');
      expect(texto('#dialogo-anulacion-titulo')).toBe('Anular recibo N.º 12');
      expect(dialogo.getAttribute('aria-labelledby')).toBe('dialogo-anulacion-titulo');
    });

    it('resume el recibo: número, cliente, fecha, medio y total', async () => {
      await abrir();

      const resumen = texto('.resumen')!;
      expect(resumen).toContain('Recibo N.º 12');
      expect(resumen).toContain('Ana Gómez');
      expect(resumen).toContain('19/10/2026 10:30');
      expect(resumen).toContain('Efectivo');
      expect(resumen).toContain('$ 700,00');
    });

    it('el medio transferencia se lee «Transferencia»', async () => {
      await abrir(recibo({ medio: 'TRANSFERENCIA' }));

      expect(texto('.resumen')).toContain('Transferencia');
    });

    it('«Vuelven a quedar pendientes:» lista cada comprobante con su importe', async () => {
      await abrir();

      expect(texto('.liberados')).toContain('Vuelven a quedar pendientes:');
      const items = Array.from(el().querySelectorAll('.liberados li')).map((li) => limpio(li.textContent));
      expect(items).toEqual(['Venta N.º 7 $ 300,00', 'Venta N.º 8 $ 400,00']);
    });

    it('si el cobro tenía importe a cuenta dice «Deja de estar a cuenta $ 500,00»', async () => {
      await abrir(recibo({ total: 1200, aCuenta: 500 }));

      expect(texto('.deja-a-cuenta')).toBe('Deja de estar a cuenta $ 500,00');
      expect(el().querySelectorAll('.liberados li')).toHaveLength(2);
    });

    it('un adelanto (sin imputaciones) no lista pendientes y sí dice que deja de estar a cuenta', async () => {
      await abrir(recibo({ total: 500, aCuenta: 500, imputaciones: [] }));

      expect(el().querySelector('.liberados')).toBeNull();
      expect(texto('.deja-a-cuenta')).toBe('Deja de estar a cuenta $ 500,00');
    });

    it('sin importe a cuenta no dice nada de «a cuenta»', async () => {
      await abrir();

      expect(el().querySelector('.deja-a-cuenta')).toBeNull();
    });

    it('avisa que no se puede deshacer (CC-097)', async () => {
      await abrir();

      expect(texto('.no-se-deshace')).toBe('Esta acción no se puede deshacer.');
    });

    it('los botones son «Cancelar» (Esc) y «Anular recibo» (Enter, de peligro)', async () => {
      await abrir();

      expect(boton('Cancelar').getAttribute('data-tecla')).toBe('Esc');
      expect(boton('Anular recibo').getAttribute('data-tecla')).toBe('Enter');
      expect(boton('Anular recibo').classList.contains('peligro')).toBe(true);
    });
  });

  describe('aviso de caja (D13, CC-083)', () => {
    it('EFECTIVO de hoy: «Esto resta $ 700,00 del efectivo esperado de la caja de hoy.» y nada más', async () => {
      await abrir(recibo(), { hoy: '2026-10-19' });

      expect(texto('.aviso-caja')).toBe('Esto resta $ 700,00 del efectivo esperado de la caja de hoy.');
      expect(el().querySelector('.aviso-otro-dia')).toBeNull();
    });

    it('EFECTIVO de otro día: el mismo aviso y además «El cobro es del 19/10: esa caja no cambia.»', async () => {
      await abrir(recibo(), { hoy: '2026-10-21' });

      expect(texto('.aviso-caja')).toBe('Esto resta $ 700,00 del efectivo esperado de la caja de hoy.');
      expect(texto('.aviso-otro-dia')).toBe('El cobro es del 19/10: esa caja no cambia.');
    });

    it('TRANSFERENCIA: «No cambia el efectivo de la caja.», sin importe ni aviso de otro día', async () => {
      await abrir(recibo({ medio: 'TRANSFERENCIA' }), { hoy: '2026-10-21' });

      expect(texto('.aviso-caja')).toBe('No cambia el efectivo de la caja.');
      expect(el().querySelector('.aviso-otro-dia')).toBeNull();
      expect(texto('.aviso-caja')).not.toContain('resta');
    });

    it('el aviso usa el total del recibo (no un importe fijo)', async () => {
      await abrir(recibo({ total: 1234.5 }));

      expect(texto('.aviso-caja')).toContain('$ 1.234,50');
    });
  });

  describe('motivo', () => {
    it('el foco inicial está en el motivo, un <input> de una línea con máximo 200 y contador', async () => {
      await abrir();

      expect(document.activeElement).toBe(motivo());
      expect(motivo().tagName).toBe('INPUT');
      expect(motivo().maxLength).toBe(200);
      expect(texto('.contador')).toBe('0/200');
      await escribir('Cliente equivocado');
      expect(texto('.contador')).toBe('18/200');
    });

    it('antes de intentar no muestra error', async () => {
      await abrir();
      await escribir('abc');

      expect(el().querySelector('.campo-error')).toBeNull();
      expect(motivo().getAttribute('aria-invalid')).toBeNull();
    });

    it('«abc» + Enter: no se envía, se marca el error y el foco queda en el motivo', async () => {
      await abrir();
      await escribir('abc');

      const evento = tecla('Enter', motivo());
      await estabilizar();

      expect(confirmadas).toEqual([]);
      expect(evento.defaultPrevented).toBe(true);
      expect(texto('#error-motivo')).toBe('Escribí el motivo de la anulación (al menos 5 letras).');
      expect(motivo().getAttribute('aria-invalid')).toBe('true');
      expect(motivo().getAttribute('aria-describedby')).toBe('error-motivo');
      expect(document.activeElement).toBe(motivo());
    });

    it('el botón «Anular recibo» con un motivo corto tampoco envía', async () => {
      await abrir();
      await escribir('abcd');

      boton('Anular recibo').click();
      await estabilizar();

      expect(confirmadas).toEqual([]);
      expect(texto('#error-motivo')).not.toBeNull();
    });

    it('vacío o solo espacios: tampoco se envía', async () => {
      await abrir();

      tecla('Enter', motivo());
      await estabilizar();
      expect(confirmadas).toEqual([]);

      await escribir('      ');
      tecla('Enter', motivo());
      await estabilizar();
      expect(confirmadas).toEqual([]);
    });

    it('una vez marcado el error, desaparece al escribir un motivo válido', async () => {
      await abrir();
      await escribir('abc');
      tecla('Enter', motivo());
      await estabilizar();
      expect(texto('#error-motivo')).not.toBeNull();

      await escribir('Cliente equivocado');

      expect(el().querySelector('#error-motivo')).toBeNull();
      expect(motivo().getAttribute('aria-invalid')).toBeNull();
    });

    it('con un motivo válido, Enter confirma con el motivo recortado', async () => {
      await abrir();
      await escribir('  Cliente equivocado  ');

      const evento = tecla('Enter', motivo());

      expect(confirmadas).toEqual(['Cliente equivocado']);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('«12345» vale: son 5 caracteres, no hace falta que sean letras', async () => {
      await abrir();
      await escribir('12345');

      boton('Anular recibo').click();

      expect(confirmadas).toEqual(['12345']);
    });

    it('un segundo Enter antes de pintar no manda la anulación dos veces', async () => {
      await abrir();
      await escribir('Cliente equivocado');

      tecla('Enter', motivo());
      tecla('Enter', motivo());

      expect(confirmadas).toEqual(['Cliente equivocado']);
    });

    it('Enter sobre el botón «Cancelar» no confirma (en un botón Enter es su clic)', async () => {
      await abrir();
      await escribir('Cliente equivocado');

      tecla('Enter', boton('Cancelar'));

      expect(confirmadas).toEqual([]);
    });
  });

  describe('Esc, envío y error del servidor', () => {
    it('Esc cierra sin anular', async () => {
      await abrir();

      const evento = tecla('Escape');

      expect(canceladas).toBe(1);
      expect(confirmadas).toEqual([]);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('el botón «Cancelar» cancela', async () => {
      await abrir();

      boton('Cancelar').click();

      expect(canceladas).toBe(1);
    });

    it('mientras se envía (enVuelo): el botón queda deshabilitado, Enter y Esc no hacen nada', async () => {
      await abrir(recibo(), { enVuelo: true });
      await escribir('Cliente equivocado');

      expect(boton('Anular recibo').disabled).toBe(true);
      boton('Anular recibo').click();
      tecla('Enter', motivo());
      tecla('Escape');

      expect(confirmadas).toEqual([]);
      expect(canceladas).toBe(0);
    });

    it('al terminar el envío (enVuelo vuelve a false) se puede reintentar', async () => {
      await abrir(recibo(), { enVuelo: true });
      await escribir('Cliente equivocado');
      fixture.componentRef.setInput('enVuelo', false);
      await estabilizar();

      boton('Anular recibo').click();

      expect(confirmadas).toEqual(['Cliente equivocado']);
    });

    it('muestra el error del servidor sin cerrarse y conserva el motivo escrito', async () => {
      await abrir(recibo(), { error: 'Abrí la caja para anular.' });
      await escribir('Cliente equivocado');

      expect(texto('.error-servidor')).toBe('Abrí la caja para anular.');
      expect(el().querySelector('[role="dialog"]')).not.toBeNull();
      expect(motivo().value).toBe('Cliente equivocado');
      expect(canceladas).toBe(0);
    });

    it('sin error del servidor no hay bloque de error', async () => {
      await abrir();

      expect(el().querySelector('.error-servidor')).toBeNull();
    });

    it('un clic en el fondo no le saca el foco al diálogo (mousedown cancelado)', async () => {
      await abrir();

      const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      el().querySelector('.dialogo-fondo')!.dispatchEvent(evento);

      expect(evento.defaultPrevented).toBe(true);
    });
  });
});
