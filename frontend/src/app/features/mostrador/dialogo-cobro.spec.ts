import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DialogoCobro, ConfirmacionCobro } from './dialogo-cobro';

const NBSP = ' ';

describe('DialogoCobro', () => {
  let fixture: ComponentFixture<DialogoCobro>;
  let confirmadas: ConfirmacionCobro[];
  let canceladas: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const importe = () => el().querySelector('input')!;
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

  async function abrir(totalCentavos = 363000) {
    await TestBed.configureTestingModule({ imports: [DialogoCobro] }).compileComponents();
    fixture = TestBed.createComponent(DialogoCobro);
    confirmadas = [];
    canceladas = 0;
    descartados = 0;
    fixture.componentRef.setInput('totalCentavos', totalCentavos);
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

  it('muestra los 4 medios en orden', async () => {
    await abrir();

    const nombres = Array.from(el().querySelectorAll('button[role="radio"]')).map((b) =>
      b.textContent!.trim(),
    );
    expect(nombres).toEqual(['Efectivo', 'Débito', 'Crédito', 'Transferencia']);
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
    await estabilizar();
    expect(elegidos()).toEqual(['Efectivo']);

    const izquierda = tecla('ArrowLeft');
    await estabilizar();
    expect(elegidos()).toEqual(['Transferencia']);
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
    expect(confirmadas).toEqual([{ medioPago: 'EFECTIVO', recibidoCentavos: 100363000 }]);
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

    expect(el().querySelector('input')).toBeNull();
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

    expect(confirmadas).toEqual([{ medioPago: 'EFECTIVO', recibidoCentavos: 363000 }]);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('Enter confirma con otro medio sin recibido', async () => {
    await abrir();
    tecla('ArrowRight');
    tecla('ArrowRight');
    await estabilizar();

    tecla('Enter');

    expect(confirmadas).toEqual([{ medioPago: 'CREDITO', recibidoCentavos: null }]);
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
});
