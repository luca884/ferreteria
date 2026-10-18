import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Botonera, AccionBotonera } from './botonera';
import { ATAJOS } from './mostrador.atajos';

describe('Botonera', () => {
  let fixture: ComponentFixture<Botonera>;
  let emitidas: AccionBotonera[];

  const el = () => fixture.nativeElement as HTMLElement;
  const botones = () => Array.from(el().querySelectorAll('button'));
  const textos = () => botones().map((b) => b.textContent!.replace(/\s+/g, ' ').trim());
  const boton = (texto: string) => botones().find((b) => b.textContent!.includes(texto))!;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Botonera] }).compileComponents();
    fixture = TestBed.createComponent(Botonera);
    emitidas = [];
    fixture.componentInstance.accion.subscribe((a) => emitidas.push(a));
    fixture.detectChanges();
  });

  it('muestra F2 Buscar, F3 Cantidad, los descuentos F7 y F6, F4 Cobrar, F8 Presupuesto, Remito (sin tecla), F9 Cliente, F10 Quitar y Vaciar', () => {
    expect(textos()).toEqual([
      'F2 Buscar',
      'F3 Cantidad',
      'F7 Descuento',
      'F6 Dto. total',
      'F4 Cobrar',
      'F8 Presupuesto',
      'Remito',
      'F9 Cliente',
      'F10 Quitar',
      'Vaciar',
    ]);
  });

  it('la tecla visible no forma parte del nombre: va en aria-keyshortcuts', () => {
    const kbds = Array.from(el().querySelectorAll('kbd'));
    expect(kbds.length).toBeGreaterThan(0);
    expect(kbds.every((k) => k.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(boton('F4').getAttribute('aria-keyshortcuts')).toBe('F4');
  });

  it('F9 Cliente va en la botonera con su tecla en aria-keyshortcuts', () => {
    expect(boton('F9').textContent).toContain('Cliente');
    expect(boton('F9').getAttribute('aria-keyshortcuts')).toBe('F9');
  });

  it('las etiquetas salen del mapa de atajos, sin duplicarlas', () => {
    const esperadas = ATAJOS.filter((a) => a.enBotonera).map((a) =>
      `${a.tecla ?? ''} ${a.etiqueta}`.trim(),
    );
    expect(textos()).toEqual(esperadas);
  });

  it('Vaciar viene del mapa: si cambia su etiqueta, cambia el botón', () => {
    const vaciar = ATAJOS.find((a) => a.accion === 'vaciar')!;
    const original = vaciar.etiqueta;
    try {
      (vaciar as { etiqueta: string }).etiqueta = 'Borrar todo';
      const f = TestBed.createComponent(Botonera);
      f.detectChanges();
      const t = Array.from((f.nativeElement as HTMLElement).querySelectorAll('button')).map((b) =>
        b.textContent!.trim(),
      );
      expect(t).toContain('Borrar todo');
      expect(t).not.toContain('Vaciar');
    } finally {
      (vaciar as { etiqueta: string }).etiqueta = original;
    }
  });

  it('el clic emite la acción', () => {
    boton('F4').click();
    boton('F3').click();
    boton('F2').click();
    boton('F8').click();
    boton('Remito').click();
    boton('F9').click();
    boton('F10').click();
    boton('Vaciar').click();

    expect(emitidas).toEqual(['cobrar', 'cantidad', 'buscar', 'presupuesto', 'remito', 'cliente', 'quitar', 'vaciar']);
  });

  it('mousedown se cancela para no robarle el foco a la caja', () => {
    const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    boton('F4').dispatchEvent(evento);

    expect(evento.defaultPrevented).toBe(true);
  });

  it('un botón deshabilitado no emite', () => {
    fixture.componentRef.setInput('habilitadas', { cobrar: false, quitar: false });
    fixture.detectChanges();

    expect(boton('F4').disabled).toBe(true);
    expect(boton('F10').disabled).toBe(true);
    expect(boton('F3').disabled).toBe(false);
    boton('F4').click();
    expect(emitidas).toEqual([]);
  });
});
