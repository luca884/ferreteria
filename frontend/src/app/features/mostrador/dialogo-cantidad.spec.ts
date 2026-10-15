import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DialogoCantidad } from './dialogo-cantidad';

describe('DialogoCantidad', () => {
  let fixture: ComponentFixture<DialogoCantidad>;
  let confirmadas: number[];
  let canceladas: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = () => el().querySelector('input')!;
  const ayuda = () => el().querySelector('.ayuda')?.textContent?.trim() ?? null;
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

  async function abrir(unidad = 'UNIDAD', cantidadMilesimas = 2000) {
    await TestBed.configureTestingModule({ imports: [DialogoCantidad] }).compileComponents();
    fixture = TestBed.createComponent(DialogoCantidad);
    confirmadas = [];
    canceladas = 0;
    descartados = 0;
    fixture.componentRef.setInput('descripcion', 'Martillo');
    fixture.componentRef.setInput('unidad', unidad);
    fixture.componentRef.setInput('cantidadMilesimas', cantidadMilesimas);
    fixture.componentInstance.confirmar.subscribe((m) => confirmadas.push(m));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
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

  it('abre con el foco en el campo y el valor actual preseleccionado', async () => {
    await abrir('METRO', 2500);

    expect(document.activeElement).toBe(campo());
    expect(campo().value).toBe('2,5');
    expect(campo().selectionStart).toBe(0);
    expect(campo().selectionEnd).toBe(3);
  });

  it.each([
    ['UNIDAD', 'Unidades — solo números enteros'],
    ['METRO', 'Metros — acepta hasta 3 decimales'],
    ['KILO', 'Kilos — acepta hasta 3 decimales'],
    ['LITRO', 'Litros — acepta hasta 3 decimales'],
  ])('dice la unidad del artículo (%s) y qué cantidades acepta (H-17)', async (unidad, texto) => {
    await abrir(unidad);

    expect(ayuda()).toBe(texto);
  });

  it('es un diálogo modal que nombra el artículo', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.textContent).toContain('Martillo');
  });

  it('Enter confirma con la cantidad en milésimas', async () => {
    await abrir();
    escribir('5');

    const evento = tecla('Enter');

    expect(confirmadas).toEqual([5000]);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('Enter con el valor sin tocar confirma la misma cantidad', async () => {
    await abrir('UNIDAD', 3000);

    tecla('Enter');

    expect(confirmadas).toEqual([3000]);
  });

  it('Esc cancela sin confirmar', async () => {
    await abrir();
    escribir('9');

    tecla('Escape');

    expect(canceladas).toBe(1);
    expect(confirmadas).toEqual([]);
  });

  it('el botón Cancelar cancela', async () => {
    await abrir();

    (el().querySelector('button.cancelar') as HTMLButtonElement).click();

    expect(canceladas).toBe(1);
  });

  it('acepta coma o punto como separador', async () => {
    await abrir('KILO', 1000);

    escribir('1,25');
    tecla('Enter');
    escribir('0.5');
    tecla('Enter');

    expect(confirmadas).toEqual([1250, 500]);
  });

  it('con 0 muestra el error y no cierra', async () => {
    await abrir();
    escribir('0');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('La cantidad tiene que ser mayor que 0.');
    expect(confirmadas).toEqual([]);
    expect(canceladas).toBe(0);
  });

  it('un artículo por unidad exige un número entero', async () => {
    await abrir('UNIDAD');
    escribir('2,5');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('Este artículo se vende por unidad: usá un número entero.');
    expect(confirmadas).toEqual([]);
  });

  it('más de 3 decimales da error', async () => {
    await abrir('KILO');
    escribir('1,2345');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('Usá hasta 3 decimales.');
    expect(confirmadas).toEqual([]);
  });

  it('un texto que no es número da error', async () => {
    await abrir();
    escribir('abc');

    tecla('Enter');
    fixture.detectChanges();

    expect(error()).toBe('Escribí una cantidad, por ejemplo 2 o 1,5.');
    expect(confirmadas).toEqual([]);
  });

  describe('accesibilidad', () => {
    const describe = () =>
      (campo().getAttribute('aria-describedby') ?? '')
        .split(' ')
        .filter(Boolean)
        .map((id) => el().querySelector(`[id="${id}"]`)?.textContent?.trim());

    it('el campo no corrige ortografía y se describe con la ayuda de la unidad', async () => {
      await abrir('METRO');

      expect(campo().getAttribute('spellcheck')).toBe('false');
      expect(describe()).toEqual(['Metros — acepta hasta 3 decimales']);
      expect(campo().hasAttribute('aria-invalid')).toBe(false);
    });

    it('con error el campo queda inválido, enlazado al mensaje y con el foco', async () => {
      await abrir();
      escribir('0');
      (el().querySelector('button.aceptar') as HTMLButtonElement).focus();

      (el().querySelector('button.aceptar') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(campo().getAttribute('aria-invalid')).toBe('true');
      expect(describe()).toEqual(['Unidades — solo números enteros', 'La cantidad tiene que ser mayor que 0.']);
      expect(document.activeElement).toBe(campo());
    });

    it('atrapa el foco: Tab en «Aceptar» vuelve al campo y Shift+Tab en el campo va a «Aceptar»', async () => {
      await abrir();
      const aceptar = el().querySelector('button.aceptar') as HTMLButtonElement;
      aceptar.focus();

      const evento = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      aceptar.dispatchEvent(evento);
      expect(document.activeElement).toBe(campo());
      expect(evento.defaultPrevented).toBe(true);

      tecla('Tab', { shiftKey: true });
      expect(document.activeElement).toBe(aceptar);
    });
  });

  it('corregir el valor después de un error confirma', async () => {
    await abrir();
    escribir('0');
    tecla('Enter');
    fixture.detectChanges();

    escribir('4');
    tecla('Enter');

    expect(confirmadas).toEqual([4000]);
  });

  describe('lector de código de barras', () => {
    function escanear(codigo: string) {
      for (const c of codigo) {
        vi.advanceTimersByTime(5);
        tecla(c);
        campo().value += c;
      }
      vi.advanceTimersByTime(5);
      return tecla('Enter');
    }

    it('una ráfaga que termina en Enter no confirma y avisa', async () => {
      await abrir('UNIDAD', 3000);
      campo().select();

      const evento = escanear('7790001');
      fixture.detectChanges();

      expect(confirmadas).toEqual([]);
      expect(descartados).toBe(1);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('la ráfaga no deja el código en el campo', async () => {
      await abrir('UNIDAD', 3000);

      escanear('7790001');
      fixture.detectChanges();

      expect(campo().value).toBe('3');
    });

    it('tipear lento y Enter sí confirma', async () => {
      await abrir('UNIDAD', 1000);
      for (const c of '12') {
        vi.advanceTimersByTime(150);
        tecla(c);
      }
      escribir('12');
      vi.advanceTimersByTime(150);

      tecla('Enter');

      expect(confirmadas).toEqual([12000]);
      expect(descartados).toBe(0);
    });
  });
});
