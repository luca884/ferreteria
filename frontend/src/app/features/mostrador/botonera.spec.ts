import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AVISO_BOTONERA_ID, Botonera, AccionBotonera } from './botonera';
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

  it('filtra las acciones visibles sin cambiar el orden del mapa de atajos', () => {
    fixture.componentRef.setInput('visibles', ['vaciar', 'cobrar', 'cantidad']);
    fixture.detectChanges();

    expect(textos()).toEqual(['Cantidad', 'Cobrar', 'Vaciar']);
  });

  it('mostrador-simple S3: `rotulos` cambia la etiqueta de una acción y `principal` la marca como primaria', () => {
    fixture.componentRef.setInput('visibles', ['presupuesto', 'quitar']);
    fixture.componentRef.setInput('rotulos', { presupuesto: 'Guardar presupuesto' });
    fixture.componentRef.setInput('principal', 'presupuesto');
    fixture.detectChanges();

    expect(textos()).toEqual(['Guardar presupuesto', 'Quitar']);
    expect(boton('Guardar presupuesto').classList).toContain('primario');
    expect(boton('Guardar presupuesto').classList).not.toContain('cobrar-fijo');
    expect(boton('Quitar').classList).not.toContain('primario');
    // La tecla sigue siendo la de la acción, no la del rótulo.
    expect(boton('Guardar presupuesto').getAttribute('aria-keyshortcuts')).toBe('F8');
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

  it('S4: una acción sin motivo se puede usar; con motivo queda aria-disabled y nunca disabled', () => {
    fixture.componentRef.setInput('motivos', { cobrar: 'Abrí la caja para cobrar.' });
    fixture.detectChanges();

    expect(boton('Cobrar').getAttribute('aria-disabled')).toBe('true');
    expect(boton('Cobrar').hasAttribute('disabled')).toBe(false);
    expect(boton('Cantidad').hasAttribute('aria-disabled')).toBe(false);
    expect(boton('Cantidad').hasAttribute('disabled')).toBe(false);
  });

  it('S4: el botón apagado igual emite, para que el mostrador explique por qué', () => {
    fixture.componentRef.setInput('motivos', {
      cobrar: 'Abrí la caja para cobrar.',
      quitar: 'Agregá un renglón para usar esta acción.',
    });
    fixture.detectChanges();

    boton('Cobrar').click();
    boton('Quitar').click();
    boton('Buscar').click();

    expect(emitidas).toEqual(['cobrar', 'quitar', 'buscar']);
  });

  it('S4: el botón apagado se ve apagado con su propia regla, sin el disabled global', () => {
    fixture.componentRef.setInput('motivos', { cobrar: 'Abrí la caja para cobrar.' });
    fixture.detectChanges();

    expect(getComputedStyle(boton('Cobrar')).cursor).toBe('not-allowed');
    expect(getComputedStyle(boton('Cantidad')).cursor).not.toBe('not-allowed');
  });

  it('S4: el botón que se está explicando se enlaza con el aviso por aria-describedby', () => {
    fixture.componentRef.setInput('motivos', { cobrar: 'Abrí la caja para cobrar.' });
    fixture.componentRef.setInput('explicada', 'cobrar');
    fixture.detectChanges();

    expect(boton('Cobrar').getAttribute('aria-describedby')).toBe(AVISO_BOTONERA_ID);
    expect(boton('Cantidad').hasAttribute('aria-describedby')).toBe(false);
  });

  it('S4: sin motivo a la vista ningún botón enlaza un aviso', () => {
    expect(botones().some((b) => b.hasAttribute('aria-describedby'))).toBe(false);
  });
});
