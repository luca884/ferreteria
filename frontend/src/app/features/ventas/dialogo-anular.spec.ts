import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DialogoAnular } from './dialogo-anular';

describe('DialogoAnular (CU-08, S2, S13, S15)', () => {
  let fixture: ComponentFixture<DialogoAnular>;
  let confirmados: (string | null)[];
  let cancelados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const motivo = () => el().querySelector<HTMLInputElement>('#motivo-anulacion');
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function abrir(extra: { conMotivo?: boolean; enVuelo?: boolean; error?: string | null } = {}) {
    await TestBed.configureTestingModule({ imports: [DialogoAnular] }).compileComponents();
    fixture = TestBed.createComponent(DialogoAnular);
    confirmados = [];
    cancelados = 0;
    fixture.componentRef.setInput('numero', 7);
    for (const [nombre, valor] of Object.entries(extra)) fixture.componentRef.setInput(nombre, valor);
    fixture.componentInstance.confirmar.subscribe((m) => confirmados.push(m));
    fixture.componentInstance.cancelar.subscribe(() => cancelados++);
    await estabilizar();
  }

  async function escribir(valor: string) {
    motivo()!.value = valor;
    motivo()!.dispatchEvent(new Event('input'));
    await estabilizar();
  }

  function tecla(key: string, destino: Element = document.activeElement ?? el()): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  it('con la factura autorizada confirma con el texto de la spec, sin motivo, y arranca en Cancelar', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="alertdialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    const titulo = limpio(el().querySelector(`#${dialogo.getAttribute('aria-labelledby')}`)?.textContent);
    const mensaje = limpio(el().querySelector(`#${dialogo.getAttribute('aria-describedby')}`)?.textContent);
    expect(`${titulo} ${mensaje}`).toBe(
      '¿Anular la venta N.º 7? Se emite una nota de crédito y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.',
    );
    expect(motivo()).toBeNull();
    expect(document.activeElement).toBe(boton('Cancelar'));

    boton('Anular').click();
    expect(confirmados).toEqual([null]);
  });

  it('sin CAE pide el motivo, obligatorio y de hasta 200, con los textos del servidor; manda el motivo recortado', async () => {
    await abrir({ conMotivo: true });

    const dialogo = el().querySelector('[role="alertdialog"]')!;
    const mensaje = limpio(el().querySelector(`#${dialogo.getAttribute('aria-describedby')}`)?.textContent);
    expect(mensaje).toBe(
      'La venta no tiene factura: queda anulada con el motivo y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.',
    );
    expect(motivo()!.maxLength).toBe(200);
    expect(limpio(el().querySelector('label[for="motivo-anulacion"]')?.textContent)).toBe('Motivo');
    expect(document.activeElement).toBe(motivo());

    boton('Anular').click();
    await estabilizar();
    expect(confirmados).toEqual([]);
    expect(limpio(el().querySelector('.campo-error')?.textContent)).toBe('Contá por qué se anula la venta.');

    await escribir('x'.repeat(201));
    expect(limpio(el().querySelector('.campo-error')?.textContent)).toBe('El motivo tiene hasta 200 caracteres.');

    await escribir('  El cliente devolvió todo ');
    tecla('Enter', motivo()!);
    expect(confirmados).toEqual(['El cliente devolvió todo']);
  });

  it('Esc y Cancelar cierran; mientras está en vuelo nada responde', async () => {
    await abrir();
    tecla('Escape');
    boton('Cancelar').click();
    expect(cancelados).toBe(2);

    TestBed.resetTestingModule();
    await abrir({ enVuelo: true });
    boton('Anular').click();
    tecla('Escape', el().querySelector('[role="alertdialog"]')!);
    expect(confirmados).toEqual([]);
    expect(cancelados).toBe(0);
    expect(boton('Anular').disabled).toBe(true);
  });

  it('muestra el rechazo del servidor tal cual, como alerta', async () => {
    await abrir({ error: 'Esperá el CAE de la factura para anularla.' });

    expect(limpio(el().querySelector('.error-servidor[role="alert"]')?.textContent)).toBe(
      'Esperá el CAE de la factura para anularla.',
    );
  });
});
