import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DialogoConfirmacion } from './dialogo-confirmacion';

describe('DialogoConfirmacion', () => {
  let fixture: ComponentFixture<DialogoConfirmacion>;
  let confirmadas: number;
  let canceladas: number;
  let descartados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const boton = (clase: string) => el().querySelector(`button.${clase}`) as HTMLButtonElement;

  function tecla(key: string): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    (document.activeElement ?? el()).dispatchEvent(evento);
    return evento;
  }

  async function abrir(entradas: Record<string, unknown>) {
    await TestBed.configureTestingModule({ imports: [DialogoConfirmacion] }).compileComponents();
    fixture = TestBed.createComponent(DialogoConfirmacion);
    confirmadas = 0;
    canceladas = 0;
    descartados = 0;
    for (const [nombre, valor] of Object.entries(entradas)) fixture.componentRef.setInput(nombre, valor);
    fixture.componentInstance.confirmar.subscribe(() => confirmadas++);
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const quitarMartillo = () => abrir({ modo: 'quitar', descripcion: 'Martillo', renglones: 3 });
  const vaciar3 = () => abrir({ modo: 'vaciar', renglones: 3 });

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('quitar', () => {
    it('nombra el renglón y enfoca «Quitar»', async () => {
      await quitarMartillo();

      expect(el().querySelector('[role="dialog"]')!.getAttribute('aria-modal')).toBe('true');
      expect(el().textContent).toContain('¿Quitar "Martillo" del ticket?');
      expect(document.activeElement).toBe(boton('confirmar'));
      expect(boton('confirmar').textContent!.trim()).toBe('Quitar');
    });

    it('Enter confirma', async () => {
      await quitarMartillo();

      const evento = tecla('Enter');

      expect(confirmadas).toBe(1);
      expect(canceladas).toBe(0);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('Esc cancela', async () => {
      await quitarMartillo();

      tecla('Escape');

      expect(canceladas).toBe(1);
      expect(confirmadas).toBe(0);
    });

    it('el botón Cancelar cancela y el botón Quitar confirma', async () => {
      await quitarMartillo();

      boton('cancelar').click();
      boton('confirmar').click();

      expect(canceladas).toBe(1);
      expect(confirmadas).toBe(1);
    });
  });

  describe('vaciar', () => {
    it('cuenta los renglones y enfoca «Cancelar»', async () => {
      await vaciar3();

      expect(el().textContent).toContain('¿Vaciar el ticket? Se pierden 3 renglones.');
      expect(document.activeElement).toBe(boton('cancelar'));
      expect(boton('confirmar').textContent!.trim()).toBe('Vaciar');
    });

    it('con un solo renglón usa el singular', async () => {
      await abrir({ modo: 'vaciar', renglones: 1 });

      expect(el().textContent).toContain('¿Vaciar el ticket? Se pierde 1 renglón.');
    });

    it('Enter con el foco en «Cancelar» no vacía', async () => {
      await vaciar3();

      tecla('Enter');

      expect(canceladas).toBe(1);
      expect(confirmadas).toBe(0);
    });

    it('Enter con el foco en «Vaciar» confirma', async () => {
      await vaciar3();
      boton('confirmar').focus();

      tecla('Enter');

      expect(confirmadas).toBe(1);
    });

    it('Esc cancela', async () => {
      await vaciar3();

      tecla('Escape');

      expect(canceladas).toBe(1);
      expect(confirmadas).toBe(0);
    });
  });

  it('una ráfaga del lector que termina en Enter no confirma y avisa', async () => {
    await quitarMartillo();

    for (const c of '7790001') {
      vi.advanceTimersByTime(5);
      tecla(c);
    }
    vi.advanceTimersByTime(5);
    const evento = tecla('Enter');

    expect(confirmadas).toBe(0);
    expect(canceladas).toBe(0);
    expect(descartados).toBe(1);
    expect(evento.defaultPrevented).toBe(true);
  });
});
