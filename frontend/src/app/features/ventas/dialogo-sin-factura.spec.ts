import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DialogoSinFactura } from './dialogo-sin-factura';

describe('DialogoSinFactura (CU-16 punto 7, C3, S12)', () => {
  let fixture: ComponentFixture<DialogoSinFactura>;
  let confirmados: string[];
  let cancelados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const motivo = () => el().querySelector<HTMLInputElement>('#motivo-sin-factura')!;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function abrir(extra: { enVuelo?: boolean; error?: string | null } = {}) {
    await TestBed.configureTestingModule({ imports: [DialogoSinFactura] }).compileComponents();
    fixture = TestBed.createComponent(DialogoSinFactura);
    confirmados = [];
    cancelados = 0;
    fixture.componentRef.setInput('numero', 7);
    if (extra.enVuelo !== undefined) fixture.componentRef.setInput('enVuelo', extra.enVuelo);
    if (extra.error !== undefined) fixture.componentRef.setInput('error', extra.error);
    fixture.componentInstance.confirmar.subscribe((m) => confirmados.push(m));
    fixture.componentInstance.cancelar.subscribe(() => cancelados++);
    await estabilizar();
  }

  async function escribir(valor: string) {
    motivo().value = valor;
    motivo().dispatchEvent(new Event('input'));
    await estabilizar();
  }

  function tecla(key: string, destino: Element = document.activeElement ?? el()): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  it('es un diálogo modal con título, avisa que es definitivo y arranca con el foco en el motivo', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(limpio(el().querySelector(`#${dialogo.getAttribute('aria-labelledby')}`)?.textContent)).toBe(
      'Dejar sin factura la venta N.º 7',
    );
    expect(limpio(el().querySelector('.definitivo')?.textContent)).toBe(
      'Es definitivo: después no se puede reintentar la factura de esta venta.',
    );
    expect(motivo().maxLength).toBe(200);
    expect(limpio(el().querySelector('label[for="motivo-sin-factura"]')?.textContent)).toBe('Motivo');
    expect(document.activeElement).toBe(motivo());
  });

  it('el motivo es obligatorio: vacío o en blanco no se manda y lo dice con el texto del servidor', async () => {
    await abrir();
    await escribir('   ');

    boton('Dejar sin factura').click();
    await estabilizar();

    expect(confirmados).toEqual([]);
    expect(limpio(el().querySelector('.campo-error')?.textContent)).toBe('Contá por qué la venta queda sin factura.');
    expect(motivo().getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(motivo());
  });

  it('con más de 200 caracteres no se manda y lo dice', async () => {
    await abrir();
    // El input corta en 200 al tipear; un valor pegado por código igual se valida.
    motivo().removeAttribute('maxlength');
    await escribir('x'.repeat(201));

    boton('Dejar sin factura').click();
    await estabilizar();

    expect(confirmados).toEqual([]);
    expect(limpio(el().querySelector('.campo-error')?.textContent)).toBe('El motivo tiene hasta 200 caracteres.');
  });

  it('Enter en el campo o el botón mandan el motivo recortado; 200 caracteres justos valen', async () => {
    await abrir();
    await escribir('  El cliente no quiso factura  ');
    tecla('Enter', motivo());
    await estabilizar();
    expect(confirmados).toEqual(['El cliente no quiso factura']);

    await escribir('y'.repeat(200));
    boton('Dejar sin factura').click();
    expect(confirmados[1]).toHaveLength(200);
  });

  it('Esc y Cancelar cierran; mientras viaja ninguno hace nada y los botones quedan deshabilitados', async () => {
    await abrir();
    tecla('Escape', motivo());
    boton('Cancelar').click();
    expect(cancelados).toBe(2);

    TestBed.resetTestingModule();
    await abrir({ enVuelo: true });
    await escribir('Motivo');
    tecla('Escape', motivo());
    tecla('Enter', motivo());
    expect(cancelados).toBe(0);
    expect(confirmados).toEqual([]);
    expect(boton('Cancelar').disabled).toBe(true);
    expect(boton('Dejar sin factura').disabled).toBe(true);
  });

  it('el rechazo del servidor se ve tal cual como alerta', async () => {
    await abrir({ error: 'Esta venta no tiene una factura rechazada para reintentar.' });

    const alerta = el().querySelector('[role="alert"]');
    expect(limpio(alerta?.textContent)).toBe('Esta venta no tiene una factura rechazada para reintentar.');
  });
});
