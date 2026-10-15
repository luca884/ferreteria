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

  it('muestra F2 Buscar, F3 Cantidad, F4 Cobrar, F10 Quitar y Vaciar', () => {
    expect(textos()).toEqual(['F2 Buscar', 'F3 Cantidad', 'F4 Cobrar', 'F10 Quitar', 'Vaciar']);
  });

  it('no muestra F7, F8 ni F9', () => {
    const todo = textos().join(' ');
    expect(todo).not.toMatch(/F7|F8|F9/);
  });

  it('las etiquetas salen del mapa de atajos, sin duplicarlas', () => {
    const esperadas = ATAJOS.filter((a) => a.enBotonera).map((a) => `${a.tecla} ${a.etiqueta}`);
    expect(textos().slice(0, esperadas.length)).toEqual(esperadas);
  });

  it('el clic emite la acción', () => {
    boton('F4').click();
    boton('F3').click();
    boton('F2').click();
    boton('F10').click();
    boton('Vaciar').click();

    expect(emitidas).toEqual(['cobrar', 'cantidad', 'buscar', 'quitar', 'vaciar']);
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
