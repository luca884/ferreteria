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

  async function abrir(entradas: { total?: number; renglones?: number | null; clienteElegido?: string | null } = {}) {
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

      expect(document.activeElement).toBe(el().querySelector('input[name="validez"]'));
    });

    it('Enter confirma sin clienteNombre: el cliente lo manda el ticket', async () => {
      await abrir({ clienteElegido: 'Ferretería Norte SRL' });

      tecla(el().querySelector('input[name="validez"]')!, 'Enter');

      expect(confirmados).toHaveLength(1);
      expect('clienteNombre' in confirmados[0]).toBe(false);
    });

    it('sin cliente elegido sigue el campo de texto libre', async () => {
      await abrir();

      expect(cliente()).not.toBeNull();
      expect(el().querySelector('.cliente-elegido')).toBeNull();
    });
  });

  /**
   * movil-sectores T10 (artboard 07 de movil-mostrador): en el celular «Guardar presupuesto» es una pantalla completa con
   * «Volver» arriba, el resumen debajo y el pie fijo con «Cancelar» y «Guardar». Es el mismo componente con el mismo estado,
   * la misma validación y los mismos manejadores: cambia dónde y cómo se ve. Vitest no lee `styles.css`; el layout se mide
   * en el navegador.
   */
  describe('en el celular: pantalla completa (movil-sectores T10)', () => {
    const comoCelular = (consulta = '639.98px') =>
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes(consulta), media: c }));
    afterEach(() => vi.unstubAllGlobals());
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const pantalla = () => el().querySelector<HTMLElement>('[role="dialog"]')!;
    const volver = () => el().querySelector<HTMLButtonElement>('.ficha-barra .ficha-volver');
    const pie = () => el().querySelector<HTMLElement>('.dialogo.presupuesto-celular > .dialogo-acciones.ficha-pie');
    const botonesDelPie = () => Array.from(pie()!.querySelectorAll('button')).map((b) => normal(b.textContent));
    const guardar = () => el().querySelector<HTMLButtonElement>('.aceptar')!;

    it('se dibuja como pantalla completa: la barra con «Volver» y el título, el resumen, los campos y el pie fijo', async () => {
      comoCelular();
      await abrir({ total: 827200, renglones: 3 });

      expect(pantalla().classList).toContain('presupuesto-celular');
      expect(pantalla().getAttribute('aria-modal')).toBe('true');
      expect(pantalla().firstElementChild!.classList).toContain('ficha-barra');
      expect(normal(el().querySelector('.ficha-barra h2')!.textContent)).toBe('Guardar presupuesto');
      expect(pantalla().getAttribute('aria-labelledby')).toBe(el().querySelector('.ficha-barra h2')!.id);
      expect(el().querySelectorAll('h2')).toHaveLength(1);
      expect(volver()!.getAttribute('aria-label')).toBe('Volver');
      expect(volver()!.getAttribute('type')).toBe('button');
      expect(normal(el().querySelector('.cabecera')!.textContent)).toBe('3 renglones · $ 8.272,00');
      expect(pie()).not.toBeNull();
      expect(botonesDelPie()).toEqual(['Cancelar', 'Guardar']);
      expect(guardar().classList).toContain('primario');
      expect(guardar().getAttribute('type')).toBe('button');
    });

    it('los campos y el foco son los de siempre: cliente (con el foco), validez en 15 días y observaciones', async () => {
      comoCelular();
      await abrir();

      expect(document.activeElement).toBe(cliente());
      expect(cliente().maxLength).toBe(80);
      expect(validez().value).toBe('15');
      expect(observaciones().maxLength).toBe(200);
    });

    it('la flecha «Volver» cancela sin guardar, aunque haya datos escritos', async () => {
      comoCelular();
      await abrir();
      escribir(cliente(), 'Obra Perez');

      volver()!.click();

      expect(cancelados).toBe(1);
      expect(confirmados).toEqual([]);
    });

    it('Esc y «Cancelar» del pie también cancelan sin guardar', async () => {
      comoCelular();
      await abrir();

      tecla(cliente(), 'Escape');
      (el().querySelector('.cancelar') as HTMLButtonElement).click();

      expect(cancelados).toBe(2);
      expect(confirmados).toEqual([]);
    });

    describe.each([
      ['celular', '639.98px'],
      ['escritorio', '__ninguna__'],
    ])('lo que se guarda es lo mismo en el %s', (_modo, consulta) => {
      it('«Guardar» manda el cliente, la validez y las observaciones que se escribieron', async () => {
        comoCelular(consulta);
        await abrir();
        escribir(cliente(), 'Obra Perez');
        escribir(validez(), '30');
        escribir(observaciones(), 'Entrega en obra, Calle 12 N.º 3140');

        guardar().click();

        expect(confirmados).toEqual([
          { clienteNombre: 'Obra Perez', diasValidez: 30, observaciones: 'Entrega en obra, Calle 12 N.º 3140' },
        ]);
      });

      it('Enter guarda con los 15 días y sin cliente ni observaciones', async () => {
        comoCelular(consulta);
        await abrir();

        tecla(observaciones(), 'Enter');

        expect(confirmados).toEqual([{ diasValidez: 15 }]);
      });

      it.each(['0', '91', '', 'abc'])('una validez de «%s» no guarda: avisa y deja el foco en la validez', async (texto) => {
        comoCelular(consulta);
        await abrir();
        escribir(validez(), texto);

        guardar().click();
        fixture.detectChanges();

        expect(confirmados).toEqual([]);
        expect(error()).toBe('La validez va de 1 a 90 días.');
        expect(validez().getAttribute('aria-invalid')).toBe('true');
        expect(document.activeElement).toBe(validez());
      });

      it('con un cliente elegido muestra «Cliente: …», no pide el texto libre y no manda clienteNombre', async () => {
        comoCelular(consulta);
        await abrir({ clienteElegido: 'Constructora del Puerto SA' });

        expect(el().querySelector('input[name="cliente"]')).toBeNull();
        expect(normal(el().querySelector('.cliente-elegido')!.textContent)).toBe('Cliente: Constructora del Puerto SA');
        expect(document.activeElement).toBe(validez());
        guardar().click();

        expect(confirmados).toEqual([{ diasValidez: 15 }]);
      });

      it('con el POST en curso «Guardar» no manda otro, y al terminar con un error se puede volver a guardar', async () => {
        comoCelular(consulta);
        await abrir();
        fixture.componentRef.setInput('enVuelo', true);
        fixture.detectChanges();

        guardar().click();
        expect(confirmados).toEqual([]);

        fixture.componentRef.setInput('enVuelo', false);
        fixture.detectChanges();
        guardar().click();

        expect(confirmados).toEqual([{ diasValidez: 15 }]);
      });

      it('muestra el aviso del contenedor y el total de la cabecera', async () => {
        comoCelular(consulta);
        await abrir({ total: 360000, renglones: 1 });
        fixture.componentRef.setInput('aviso', 'Cambió el precio de "Tornillo": ahora $ 180,00.');
        fixture.detectChanges();

        expect(normal(el().querySelector('[role="status"]')!.textContent)).toBe('Cambió el precio de "Tornillo": ahora $ 180,00.');
        expect(normal(el().querySelector('.cabecera')!.textContent)).toContain('1 renglón · $ 3.600,00');
      });
    });

    describe('fuera del celular el diálogo no cambia', () => {
      it.each([
        ['escritorio', '__ninguna__'],
        ['tablet (hasta 1023 px)', '1023.98px'],
      ])('en %s sigue siendo el diálogo centrado, con el título en la cabecera, sin barra ni pie fijo', async (_nombre, consulta) => {
        comoCelular(consulta);
        await abrir();

        expect(el().querySelector('.presupuesto-celular, .ficha-barra, .ficha-volver, .ficha-pie')).toBeNull();
        expect(normal(el().querySelector('.cabecera h2')!.textContent)).toBe('Guardar presupuesto');
        expect(Array.from(el().querySelectorAll('.dialogo-acciones button')).map((b) => normal(b.textContent))).toEqual(['Cancelar', 'Guardar']);
      });

      it('sin matchMedia (pruebas, impresión) tampoco', async () => {
        await abrir();

        expect(el().querySelector('.presupuesto-celular, .ficha-barra')).toBeNull();
      });
    });
  });
});
