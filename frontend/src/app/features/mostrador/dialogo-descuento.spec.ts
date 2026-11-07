import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DialogoDescuento } from './dialogo-descuento';

describe('DialogoDescuento', () => {
  let fixture: ComponentFixture<DialogoDescuento>;
  let confirmados: number[];
  let cancelados: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = () => el().querySelector('input')!;
  const error = () => el().querySelector('[role="alert"]')?.textContent?.trim() ?? null;

  function tecla(key: string, init: KeyboardEventInit = {}): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true, ...init });
    campo().dispatchEvent(evento);
    return evento;
  }

  function escribir(texto: string) {
    campo().value = texto;
    campo().dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  async function abrir(descuentoBp = 0, titulo = 'Descuento de Martillo', topeBp: number | null = null) {
    await TestBed.configureTestingModule({ imports: [DialogoDescuento] }).compileComponents();
    fixture = TestBed.createComponent(DialogoDescuento);
    confirmados = [];
    cancelados = 0;
    descartados = 0;
    fixture.componentRef.setInput('titulo', titulo);
    fixture.componentRef.setInput('descuentoBp', descuentoBp);
    fixture.componentRef.setInput('topeBp', topeBp);
    fixture.componentInstance.confirmar.subscribe((bp) => confirmados.push(bp));
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

  it('es un diálogo modal con el título que le da el contenedor', async () => {
    await abrir(0, 'Descuento de todo el ticket');

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.querySelector('h2')!.textContent).toContain('Descuento de todo el ticket');
  });

  it('abre con el foco en el campo y el descuento actual preseleccionado', async () => {
    await abrir(1050);

    expect(document.activeElement).toBe(campo());
    expect(campo().value).toBe('10,5');
    expect(campo().selectionStart).toBe(0);
    expect(campo().selectionEnd).toBe(4);
  });

  it('sin descuento abre con 0', async () => {
    await abrir(0);

    expect(campo().value).toBe('0');
  });

  it('dice qué acepta: porcentaje de 0 a 99,99 y que 0 lo saca', async () => {
    await abrir();

    expect(el().querySelector('.ayuda')!.textContent).toContain('0 a 99,99');
    expect(el().querySelector('.ayuda')!.textContent).toContain('0 lo saca');
  });

  it('Enter confirma el porcentaje en centésimas de punto', async () => {
    await abrir();
    escribir('15');

    const evento = tecla('Enter');

    expect(confirmados).toEqual([1500]);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('acepta coma decimal: 7,5 son 750', async () => {
    await abrir();
    escribir('7,5');

    tecla('Enter');

    expect(confirmados).toEqual([750]);
  });

  it('0 se confirma: es sacar el descuento', async () => {
    await abrir(1000);
    escribir('0');

    tecla('Enter');

    expect(confirmados).toEqual([0]);
  });

  it('Enter con el valor sin tocar confirma el mismo descuento', async () => {
    await abrir(2000);

    tecla('Enter');

    expect(confirmados).toEqual([2000]);
  });

  it.each([
    ['100', 'El descuento tiene que estar entre 0 y 99,99 %.'],
    ['150', 'El descuento tiene que estar entre 0 y 99,99 %.'],
    ['-5', 'Escribí un porcentaje, por ejemplo 10 o 7,5.'],
    ['abc', 'Escribí un porcentaje, por ejemplo 10 o 7,5.'],
    ['', 'Escribí un porcentaje, por ejemplo 10 o 7,5.'],
    ['10,555', 'Usá hasta 2 decimales.'],
  ])('«%s» no se confirma y avisa: %s', async (texto, mensaje) => {
    await abrir();
    escribir(texto);

    tecla('Enter');
    fixture.detectChanges();

    expect(confirmados).toEqual([]);
    expect(error()).toBe(mensaje);
    expect(document.activeElement).toBe(campo());
  });

  describe('con tope de descuento (CU-07)', () => {
    it('la ayuda dice el tope del usuario', async () => {
      await abrir(0, 'Descuento de Martillo', 1250);

      expect(el().querySelector('.ayuda')!.textContent).toContain('Tu tope es 12,5 %');
    });

    it('igual al tope se confirma', async () => {
      await abrir(0, 'Descuento de Martillo', 1000);
      escribir('10');

      tecla('Enter');

      expect(confirmados).toEqual([1000]);
    });

    it('sobre el tope no se confirma y avisa con el tope', async () => {
      await abrir(0, 'Descuento de Martillo', 1000);
      escribir('10,01');

      tecla('Enter');
      fixture.detectChanges();

      expect(confirmados).toEqual([]);
      expect(error()).toBe('Tu tope de descuento es 10 %.');
      expect(document.activeElement).toBe(campo());
    });

    describe('sumado al otro descuento (arreglos-confusos, S6)', () => {
      function conOtro(bp: number, es: 'renglon' | 'general') {
        fixture.componentRef.setInput('otroDescuentoBp', bp);
        fixture.componentRef.setInput('otroDescuentoEs', es);
        fixture.detectChanges();
      }

      it('F6: 5 % general con 10 % en un renglón pasa el tope de 10 % (14,5 % efectivo): avisa y no aplica', async () => {
        await abrir(0, 'Descuento de todo el ticket', 1000);
        conOtro(1000, 'renglon');
        escribir('5');

        tecla('Enter');
        fixture.detectChanges();

        expect(confirmados).toEqual([]);
        expect(error()).toBe('Sumado al descuento del renglón, pasa tu tope de descuento de 10 %.');
        expect(document.activeElement).toBe(campo());
      });

      it('F7: 6 % en el renglón con 5 % general pasa el tope de 10 %: avisa con el texto del servidor', async () => {
        await abrir(0, 'Descuento de Martillo', 1000);
        conOtro(500, 'general');
        escribir('6');

        tecla('Enter');
        fixture.detectChanges();

        expect(confirmados).toEqual([]);
        expect(error()).toBe('Sumado al descuento general, pasa tu tope de descuento de 10 %.');
      });

      it('con el otro en 10 % y este en 0 % pasa: 0 lo saca', async () => {
        await abrir(500, 'Descuento de todo el ticket', 1000);
        conOtro(1000, 'renglon');
        escribir('0');

        tecla('Enter');

        expect(confirmados).toEqual([0]);
      });

      it('si la combinación queda igual al tope, pasa', async () => {
        await abrir(0, 'Descuento de todo el ticket', 1000);
        conOtro(500, 'renglon');
        // 100 − 95 × 94,74 / 100 = 9,997 %: no llega al 10 %.
        escribir('5,26');

        tecla('Enter');

        expect(confirmados).toEqual([526]);
      });

      it('sin tope (el Dueño) no avisa nada', async () => {
        await abrir(0, 'Descuento de todo el ticket', null);
        conOtro(5000, 'renglon');
        escribir('50');

        tecla('Enter');

        expect(confirmados).toEqual([5000]);
      });
    });
  });

  it('99,99 es el máximo y se confirma', async () => {
    await abrir();
    escribir('99,99');

    tecla('Enter');

    expect(confirmados).toEqual([9999]);
  });

  it('Esc cancela sin confirmar', async () => {
    await abrir();
    escribir('9');

    tecla('Escape');

    expect(cancelados).toBe(1);
    expect(confirmados).toEqual([]);
  });

  it('los botones Cancelar y Aceptar hacen lo mismo que Esc y Enter', async () => {
    await abrir();
    escribir('12');

    (el().querySelector('.aceptar') as HTMLButtonElement).click();
    (el().querySelector('.cancelar') as HTMLButtonElement).click();

    expect(confirmados).toEqual([1200]);
    expect(cancelados).toBe(1);
  });

  it('un escaneo (Enter en ráfaga) se descarta, restaura el valor y avisa', async () => {
    await abrir(1000);
    for (const c of '7790001') {
      vi.advanceTimersByTime(5);
      tecla(c);
      campo().value += c;
    }
    vi.advanceTimersByTime(5);

    tecla('Enter');

    expect(confirmados).toEqual([]);
    expect(descartados).toBe(1);
    expect(campo().value).toBe('10');
  });
});
