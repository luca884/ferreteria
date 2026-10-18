import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DatosRemito } from '../remitos/remito.models';
import { DialogoRemito } from './dialogo-remito';

registerLocaleData(localeEsAr);

describe('DialogoRemito', () => {
  let fixture: ComponentFixture<DialogoRemito>;
  let confirmados: DatosRemito[];
  let cancelados: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const cliente = () => el().querySelector<HTMLInputElement>('input[name="cliente"]')!;
  const observaciones = () => el().querySelector<HTMLInputElement>('input[name="observaciones"]')!;

  function tecla(destino: HTMLElement, key: string, init: KeyboardEventInit = {}): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true, ...init });
    destino.dispatchEvent(evento);
    return evento;
  }

  function escribir(campo: HTMLInputElement, texto: string) {
    campo.value = texto;
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  async function abrir(entradas: { total?: number; renglones?: number | null; clienteElegido?: string | null } = {}) {
    await TestBed.configureTestingModule({
      imports: [DialogoRemito],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(DialogoRemito);
    confirmados = [];
    cancelados = 0;
    descartados = 0;
    fixture.componentRef.setInput('totalCentavos', entradas.total ?? 51000);
    fixture.componentRef.setInput('renglones', entradas.renglones === undefined ? 2 : entradas.renglones);
    fixture.componentRef.setInput('clienteElegido', entradas.clienteElegido ?? null);
    fixture.componentInstance.confirmar.subscribe((datos) => confirmados.push(datos));
    fixture.componentInstance.cancelar.subscribe(() => cancelados++);
    fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('es un diálogo modal «Emitir remito» con el total y los renglones del ticket', async () => {
    await abrir({ total: 51000, renglones: 2 });

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.querySelector('h2')!.textContent).toContain('Emitir remito');
    expect(dialogo.textContent).toContain('510,00');
    expect(dialogo.textContent).toContain('2 renglones');
  });

  it('avisa que el remito descuenta el stock', async () => {
    await abrir();

    expect(el().textContent).toContain('descuenta el stock');
  });

  it('abre con el foco en el cliente, y sin validez', async () => {
    await abrir();

    expect(document.activeElement).toBe(cliente());
    expect(cliente().value).toBe('');
    expect(observaciones().value).toBe('');
    expect(el().querySelector('input[name="validez"]')).toBeNull();
  });

  it('Enter en el cliente emite sin cliente ni observaciones', async () => {
    await abrir();

    const evento = tecla(cliente(), 'Enter');

    expect(evento.defaultPrevented).toBe(true);
    expect(confirmados).toEqual([{}]);
  });

  it('manda el cliente y las observaciones que se escribieron', async () => {
    await abrir();
    escribir(cliente(), 'Obra Perez');
    escribir(observaciones(), 'Entrega el viernes');

    tecla(observaciones(), 'Enter');

    expect(confirmados).toEqual([{ clienteNombre: 'Obra Perez', observaciones: 'Entrega el viernes' }]);
  });

  it('el cliente admite hasta 80 caracteres y las observaciones hasta 200', async () => {
    await abrir();

    expect(cliente().maxLength).toBe(80);
    expect(observaciones().maxLength).toBe(200);
  });

  it('Esc cancela sin emitir', async () => {
    await abrir();
    escribir(cliente(), 'Obra');

    const evento = tecla(cliente(), 'Escape');

    expect(evento.defaultPrevented).toBe(true);
    expect(cancelados).toBe(1);
    expect(confirmados).toEqual([]);
  });

  it('los botones Cancelar y Emitir hacen lo mismo que Esc y Enter', async () => {
    await abrir();
    escribir(cliente(), 'Obra');

    (el().querySelector('.aceptar') as HTMLButtonElement).click();
    (el().querySelector('.cancelar') as HTMLButtonElement).click();

    expect(confirmados).toEqual([{ clienteNombre: 'Obra' }]);
    expect(cancelados).toBe(1);
  });

  it('un escaneo (Enter en ráfaga) se descarta, vacía el campo y avisa', async () => {
    await abrir();
    for (const c of '7790001') {
      vi.advanceTimersByTime(5);
      tecla(cliente(), c);
      cliente().value += c;
    }
    vi.advanceTimersByTime(5);

    tecla(cliente(), 'Enter');

    expect(confirmados).toEqual([]);
    expect(descartados).toBe(1);
    expect(cliente().value).toBe('');
  });

  it('con el POST en curso ignora Enter y el botón; al terminar con un error vuelve a emitir', async () => {
    await abrir();
    fixture.componentRef.setInput('enVuelo', true);
    fixture.detectChanges();

    tecla(cliente(), 'Enter');
    (el().querySelector('.aceptar') as HTMLButtonElement).click();
    expect(confirmados).toEqual([]);

    fixture.componentRef.setInput('enVuelo', false);
    fixture.detectChanges();
    tecla(cliente(), 'Enter');

    expect(confirmados).toHaveLength(1);
  });

  it('después de confirmar no manda dos veces hasta que el contenedor termine', async () => {
    await abrir();

    tecla(cliente(), 'Enter');
    tecla(cliente(), 'Enter');

    expect(confirmados).toHaveLength(1);
  });

  it('muestra el aviso del contenedor (un precio que cambió, un error)', async () => {
    await abrir();
    fixture.componentRef.setInput('aviso', 'Cambió el precio de "Tornillo": ahora $ 180,00.');
    fixture.detectChanges();

    expect(el().querySelector('[role="status"]')!.textContent).toContain('Cambió el precio de "Tornillo"');
  });

  describe('con un cliente elegido con F9 (CL-005)', () => {
    it('muestra el cliente en vez del campo de texto libre', async () => {
      await abrir({ clienteElegido: 'Ferretería Norte SRL' });

      expect(el().querySelector('input[name="cliente"]')).toBeNull();
      expect(el().querySelector('.cliente-elegido')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'Cliente: Ferretería Norte SRL',
      );
    });

    it('el foco va al primer campo que queda', async () => {
      await abrir({ clienteElegido: 'Ferretería Norte SRL' });

      expect(document.activeElement).toBe(el().querySelector('input[name="observaciones"]'));
    });

    it('Enter confirma sin clienteNombre: el cliente lo manda el ticket', async () => {
      await abrir({ clienteElegido: 'Ferretería Norte SRL' });

      tecla(el().querySelector('input[name="observaciones"]')!, 'Enter');

      expect(confirmados).toHaveLength(1);
      expect('clienteNombre' in confirmados[0]).toBe(false);
    });

    it('sin cliente elegido sigue el campo de texto libre', async () => {
      await abrir();

      expect(cliente()).not.toBeNull();
      expect(el().querySelector('.cliente-elegido')).toBeNull();
    });
  });
});
