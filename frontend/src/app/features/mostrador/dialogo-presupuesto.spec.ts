import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DatosPresupuesto } from '../presupuestos/presupuesto.models';
import { DialogoPresupuesto } from './dialogo-presupuesto';

registerLocaleData(localeEsAr);

describe('DialogoPresupuesto', () => {
  let fixture: ComponentFixture<DialogoPresupuesto>;
  let confirmados: DatosPresupuesto[];
  let cancelados: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const cliente = () => el().querySelector<HTMLInputElement>('input[name="cliente"]')!;
  const validez = () => el().querySelector<HTMLInputElement>('input[name="validez"]')!;
  const observaciones = () => el().querySelector<HTMLInputElement>('input[name="observaciones"]')!;
  const error = () => el().querySelector('[role="alert"]')?.textContent?.trim() ?? null;

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

  async function abrir(entradas: { total?: number; renglones?: number | null } = {}) {
    await TestBed.configureTestingModule({
      imports: [DialogoPresupuesto],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(DialogoPresupuesto);
    confirmados = [];
    cancelados = 0;
    descartados = 0;
    fixture.componentRef.setInput('totalCentavos', entradas.total ?? 51000);
    fixture.componentRef.setInput('renglones', entradas.renglones === undefined ? 2 : entradas.renglones);
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

  it('es un diálogo modal «Guardar presupuesto» con el total y los renglones del ticket', async () => {
    await abrir({ total: 51000, renglones: 2 });

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.querySelector('h2')!.textContent).toContain('Guardar presupuesto');
    expect(dialogo.textContent).toContain('510,00');
    expect(dialogo.textContent).toContain('2 renglones');
  });

  it('abre con el foco en el cliente y la validez en 15 días', async () => {
    await abrir();

    expect(document.activeElement).toBe(cliente());
    expect(cliente().value).toBe('');
    expect(validez().value).toBe('15');
    expect(observaciones().value).toBe('');
  });

  it('Enter en el cliente guarda con los 15 días y sin cliente ni observaciones', async () => {
    await abrir();

    const evento = tecla(cliente(), 'Enter');

    expect(evento.defaultPrevented).toBe(true);
    expect(confirmados).toEqual([{ diasValidez: 15 }]);
  });

  it('manda el cliente, la validez y las observaciones que se escribieron', async () => {
    await abrir();
    escribir(cliente(), 'Obra Perez');
    escribir(validez(), '30');
    escribir(observaciones(), 'Entrega el viernes');

    tecla(observaciones(), 'Enter');

    expect(confirmados).toEqual([{ clienteNombre: 'Obra Perez', diasValidez: 30, observaciones: 'Entrega el viernes' }]);
  });

  it('Enter en la validez también guarda', async () => {
    await abrir();
    escribir(validez(), '7');

    tecla(validez(), 'Enter');

    expect(confirmados).toEqual([{ diasValidez: 7 }]);
  });

  it.each([1, 90])('%i días valen', async (dias) => {
    await abrir();
    escribir(validez(), String(dias));

    tecla(validez(), 'Enter');

    expect(confirmados).toEqual([{ diasValidez: dias }]);
  });

  it.each(['0', '91', '', 'abc', '1,5', '-3'])(
    'una validez de «%s» no guarda: avisa que va de 1 a 90 días y deja el foco en la validez',
    async (texto) => {
      await abrir();
      escribir(validez(), texto);

      tecla(validez(), 'Enter');
      fixture.detectChanges();

      expect(confirmados).toEqual([]);
      expect(error()).toBe('La validez va de 1 a 90 días.');
      expect(document.activeElement).toBe(validez());
    },
  );

  it('al corregir la validez el error se va', async () => {
    await abrir();
    escribir(validez(), '0');
    tecla(validez(), 'Enter');
    fixture.detectChanges();

    escribir(validez(), '10');
    tecla(validez(), 'Enter');
    fixture.detectChanges();

    expect(error()).toBeNull();
    expect(confirmados).toEqual([{ diasValidez: 10 }]);
  });

  it('el cliente admite hasta 80 caracteres y las observaciones hasta 200', async () => {
    await abrir();

    expect(cliente().maxLength).toBe(80);
    expect(observaciones().maxLength).toBe(200);
  });

  it('Esc cancela sin guardar', async () => {
    await abrir();
    escribir(cliente(), 'Obra');

    const evento = tecla(cliente(), 'Escape');

    expect(evento.defaultPrevented).toBe(true);
    expect(cancelados).toBe(1);
    expect(confirmados).toEqual([]);
  });

  it('los botones Cancelar y Guardar hacen lo mismo que Esc y Enter', async () => {
    await abrir();
    escribir(cliente(), 'Obra');

    (el().querySelector('.aceptar') as HTMLButtonElement).click();
    (el().querySelector('.cancelar') as HTMLButtonElement).click();

    expect(confirmados).toEqual([{ clienteNombre: 'Obra', diasValidez: 15 }]);
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

  it('con el POST en curso ignora Enter y el botón; al terminar con un error vuelve a guardar', async () => {
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
});
