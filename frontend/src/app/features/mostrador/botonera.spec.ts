import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Botonera, AccionBotonera } from './botonera';
import { ATAJOS } from './mostrador.atajos';

describe('Botonera', () => {
  let fixture: ComponentFixture<Botonera>;
  let emitidas: AccionBotonera[];

  const el = () => fixture.nativeElement as HTMLElement;
  const botones = () => Array.from(el().querySelectorAll('button'));
  const textos = () => botones().map((b) => b.textContent!.replace(/\s+/g, ' ').trim());
  const boton = (texto: string) => botones().find((b) => b.textContent!.trim() === texto)!;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Botonera] }).compileComponents();
    fixture = TestBed.createComponent(Botonera);
    emitidas = [];
    fixture.componentInstance.accion.subscribe((a) => emitidas.push(a));
    fixture.detectChanges();
  });

  it('muestra Buscar, Cantidad, los descuentos, Cobrar, Presupuesto, Remito, Cliente, Quitar y Vaciar, sin la tecla', () => {
    expect(textos()).toEqual([
      'Buscar',
      'Cantidad',
      'Descuento',
      'Dto. total',
      'Cobrar',
      'Presupuesto',
      'Remito',
      'Cliente',
      'Quitar',
      'Vaciar',
    ]);
  });

  it('la tecla no se ve: no hay ningún kbd y va en aria-keyshortcuts', () => {
    expect(el().querySelectorAll('kbd')).toHaveLength(0);
    expect(boton('Cobrar').getAttribute('aria-keyshortcuts')).toBe('F4');
    expect(boton('Cliente').getAttribute('aria-keyshortcuts')).toBe('F9');
  });

  it('S6: solo Cobrar lleva la clase que lo fija en la barra de abajo en el celular', () => {
    expect(boton('Cobrar').classList).toContain('cobrar-fijo');
    expect(botones().filter((b) => b.classList.contains('cobrar-fijo'))).toHaveLength(1);
  });

  it('Cliente va en la botonera con su tecla en aria-keyshortcuts', () => {
    expect(boton('Cliente').getAttribute('aria-keyshortcuts')).toBe('F9');
  });

  it('las etiquetas salen del mapa de atajos, sin duplicarlas', () => {
    const esperadas = ATAJOS.filter((a) => a.enBotonera).map((a) => a.etiqueta);
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
    boton('Cobrar').click();
    boton('Cantidad').click();
    boton('Buscar').click();
    boton('Presupuesto').click();
    boton('Remito').click();
    boton('Cliente').click();
    boton('Quitar').click();
    boton('Vaciar').click();

    expect(emitidas).toEqual(['cobrar', 'cantidad', 'buscar', 'presupuesto', 'remito', 'cliente', 'quitar', 'vaciar']);
  });

  it('mousedown se cancela para no robarle el foco a la caja', () => {
    const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    boton('Cobrar').dispatchEvent(evento);

    expect(evento.defaultPrevented).toBe(true);
  });

  it('un botón deshabilitado no emite', () => {
    fixture.componentRef.setInput('habilitadas', { cobrar: false, quitar: false });
    fixture.detectChanges();

    expect(boton('Cobrar').disabled).toBe(true);
    expect(boton('Quitar').disabled).toBe(true);
    expect(boton('Cantidad').disabled).toBe(false);
    boton('Cobrar').click();
    expect(emitidas).toEqual([]);
  });
});
