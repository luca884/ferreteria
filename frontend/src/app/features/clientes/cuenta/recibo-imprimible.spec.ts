import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Recibo } from './cobranza.models';
import { ReciboImprimible } from './recibo-imprimible';

registerLocaleData(localeEsAr);

describe('ReciboImprimible (CC-036, CC-043)', () => {
  const recibo = (cambios: Partial<Recibo> = {}): Recibo => ({
    id: 5,
    numero: 12,
    fecha: '2026-10-19T10:30:00',
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    medio: 'EFECTIVO',
    total: 1500,
    aCuenta: 500,
    cobradoPor: 'Dueño Tornillo',
    imputaciones: [{ comprobanteId: 31, referencia: 'Venta N.º 7', importe: 1000 }],
    ...cambios,
  });

  let fixture: ComponentFixture<ReciboImprimible>;
  let cerradas: number;
  let imprimir: ReturnType<typeof vi.spyOn>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;

  function tecla(key: string, destino: Element = el().querySelector('[role="dialog"]')!): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function abrir(r: Recibo = recibo(), ferreteriaNombre: string | null = 'Tornillo SA') {
    imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [ReciboImprimible],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ReciboImprimible);
    cerradas = 0;
    fixture.componentRef.setInput('recibo', r);
    fixture.componentInstance.cerrar.subscribe(() => cerradas++);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => imprimir?.mockRestore());

  it('es un diálogo modal titulado con el número del recibo', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(texto('#recibo-titulo')).toBe('Recibo N.º 12');
    expect(dialogo.getAttribute('aria-labelledby')).toBe('recibo-titulo');
  });

  it('muestra la ferretería de la sesión, la fecha completa, el cliente, el medio, quién cobró y el total', async () => {
    await abrir();

    const recibido = texto('.recibo-imprimible')!;
    expect(texto('.ferreteria')).toBe('Tornillo SA');
    expect(recibido).toContain('19/10/2026 10:30');
    expect(recibido).toContain('Ana Gómez');
    expect(recibido).toContain('Efectivo');
    expect(recibido).toContain('Dueño Tornillo');
    expect(texto('.total')).toContain('$ 1.500,00');
  });

  it('el medio transferencia se lee «Transferencia»', async () => {
    await abrir(recibo({ medio: 'TRANSFERENCIA' }));

    expect(texto('.recibo-imprimible')).toContain('Transferencia');
  });

  it('lista cada imputación con la referencia del comprobante y su importe', async () => {
    await abrir(
      recibo({
        total: 600,
        aCuenta: 0,
        imputaciones: [
          { comprobanteId: 31, referencia: 'Venta N.º 7', importe: 300 },
          { comprobanteId: 32, referencia: 'Venta N.º 8', importe: 300 },
        ],
      }),
    );

    const filas = Array.from(el().querySelectorAll('tbody tr')).map(
      (tr) => `${limpio(tr.querySelector('th')?.textContent)} ${limpio(tr.querySelector('td')?.textContent)}`,
    );
    expect(filas).toEqual(['Venta N.º 7 $ 300,00', 'Venta N.º 8 $ 300,00']);
  });

  it('muestra «A cuenta $X» cuando quedó algo sin imputar', async () => {
    await abrir();

    expect(texto('.a-cuenta')).toBe('A cuenta $ 500,00');
  });

  it('sin excedente no hay línea «A cuenta»', async () => {
    await abrir(recibo({ total: 1000, aCuenta: 0 }));

    expect(el().querySelector('.a-cuenta')).toBeNull();
  });

  it('un adelanto (sin imputaciones) no tiene tabla y todo el total figura «A cuenta»', async () => {
    await abrir(recibo({ total: 500, aCuenta: 500, imputaciones: [] }));

    expect(el().querySelector('table')).toBeNull();
    expect(texto('.a-cuenta')).toBe('A cuenta $ 500,00');
    expect(texto('.total')).toContain('$ 500,00');
  });

  it('lleva la leyenda de recibo interno no fiscal', async () => {
    await abrir();

    expect(texto('.leyenda')).toBe('Recibo interno, no válido como comprobante fiscal');
  });

  it('sin nombre de ferretería en la sesión no muestra el encabezado ni escribe «null»', async () => {
    await abrir(recibo(), null);

    expect(el().querySelector('.ferreteria')).toBeNull();
    expect(el().textContent).not.toContain('null');
  });

  describe('imprimir y cerrar', () => {
    it('el botón Imprimir llama a window.print', async () => {
      await abrir();

      boton('Imprimir').click();

      expect(imprimir).toHaveBeenCalledTimes(1);
    });

    it('Enter imprime y Esc cierra', async () => {
      await abrir();

      const enter = tecla('Enter');
      expect(imprimir).toHaveBeenCalledTimes(1);
      expect(enter.defaultPrevented).toBe(true);

      const esc = tecla('Escape');
      expect(cerradas).toBe(1);
      expect(esc.defaultPrevented).toBe(true);
    });

    it('Enter sobre «Cerrar» no imprime: lo resuelve el botón', async () => {
      await abrir();

      tecla('Enter', boton('Cerrar'));

      expect(imprimir).not.toHaveBeenCalled();
    });

    it('el botón Cerrar emite cerrar', async () => {
      await abrir();

      boton('Cerrar').click();

      expect(cerradas).toBe(1);
    });

    it('el foco inicial queda en Imprimir (así Enter imprime)', async () => {
      await abrir();

      expect(document.activeElement).toBe(boton('Imprimir'));
    });

    it('los botones quedan fuera del recibo que se imprime', async () => {
      await abrir();

      const recibido = el().querySelector('.recibo-imprimible')!;
      expect(recibido.querySelector('button')).toBeNull();
    });
  });
});
