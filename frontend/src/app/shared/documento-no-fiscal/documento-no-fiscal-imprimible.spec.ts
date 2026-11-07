import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DocumentoNoFiscalImprimible } from './documento-no-fiscal-imprimible';
import { documentoDePrueba, emisorDePrueba } from './documento-no-fiscal.testing';

registerLocaleData(localeEsAr);

describe('DocumentoNoFiscalImprimible', () => {
  let fixture: ComponentFixture<DocumentoNoFiscalImprimible>;
  let cerradas: number;
  let imprimir: ReturnType<typeof vi.spyOn>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => limpio(b.textContent) === nombre,
    )!;

  function tecla(
    key: string,
    destino: Element = el().querySelector('[role="dialog"]')!,
  ): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  beforeEach(async () => {
    imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [DocumentoNoFiscalImprimible],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(DocumentoNoFiscalImprimible);
    cerradas = 0;
    fixture.componentRef.setInput('documento', documentoDePrueba());
    fixture.componentRef.setInput('emisor', emisorDePrueba());
    fixture.componentInstance.cerrar.subscribe(() => cerradas++);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => imprimir.mockRestore());

  it('es un diálogo modal titulado con el documento, con la hoja adentro y los botones afuera de ella', () => {
    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.getAttribute('aria-labelledby')).toBe('documento-titulo');
    expect(limpio(el().querySelector('#documento-titulo')?.textContent)).toBe('Remito N.º 12');
    const articulo = el().querySelector('article.documento-imprimible')!;
    expect(articulo.closest('.dialogo-fondo > [role="dialog"]')).not.toBeNull();
    expect(articulo.querySelector('app-documento-no-fiscal-hoja')).not.toBeNull();
    const botones = Array.from(el().querySelectorAll('button'));
    expect(botones.map((b) => limpio(b.textContent))).toEqual(['Cerrar', 'Imprimir']);
    expect(botones.every((b) => !articulo.contains(b))).toBe(true);
  });

  it('Imprimir y Enter imprimen; Cerrar y Esc cierran', () => {
    boton('Imprimir').click();
    expect(imprimir).toHaveBeenCalledTimes(1);
    expect(tecla('Enter').defaultPrevented).toBe(true);
    expect(imprimir).toHaveBeenCalledTimes(2);
    boton('Cerrar').click();
    expect(cerradas).toBe(1);
    expect(tecla('Escape').defaultPrevented).toBe(true);
    expect(cerradas).toBe(2);
  });

  it('Enter sobre un botón no imprime dos veces: es su clic', () => {
    tecla('Enter', boton('Cerrar'));

    expect(imprimir).not.toHaveBeenCalled();
  });

  it('arranca con el foco en Imprimir', () => {
    expect(document.activeElement).toBe(boton('Imprimir'));
  });

  describe('abierta desde el mostrador (informacion-y-links, S1)', () => {
    it('un Enter que cierra un escaneo no imprime (tampoco sobre el botón) y avisa', () => {
      let descartados = 0;
      fixture.componentInstance.escaneoDescartado.subscribe(() => descartados++);
      const esEscaneo = vi.fn((evento: KeyboardEvent) => evento.key === 'Enter');
      fixture.componentRef.setInput('esEscaneo', esEscaneo);
      fixture.detectChanges();

      tecla('7');
      const enter = tecla('Enter', boton('Imprimir'));

      expect(esEscaneo).toHaveBeenCalledTimes(2);
      expect(enter.defaultPrevented).toBe(true);
      expect(imprimir).not.toHaveBeenCalled();
      expect(descartados).toBe(1);
    });

    it('muestra el aviso del contenedor (el escaneo descartado), fuera de lo que se imprime', () => {
      fixture.componentRef.setInput('aviso', 'Cerrá el diálogo antes de escanear.');
      fixture.detectChanges();

      const aviso = el().querySelector('[role="status"]')!;
      expect(limpio(aviso.textContent)).toBe('Cerrá el diálogo antes de escanear.');
      expect(el().querySelector('article.documento-imprimible')!.contains(aviso)).toBe(false);
    });
  });
});
