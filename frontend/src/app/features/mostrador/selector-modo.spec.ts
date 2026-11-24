import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModoMostrador, SelectorModo } from './selector-modo';

describe('SelectorModo', () => {
  let fixture: ComponentFixture<SelectorModo>;
  let elegidos: ModoMostrador[];

  const el = () => fixture.nativeElement as HTMLElement;
  const botones = () => Array.from(el().querySelectorAll<HTMLButtonElement>('button'));
  const boton = (texto: string) => botones().find((b) => b.textContent!.trim() === texto)!;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SelectorModo] }).compileComponents();
    fixture = TestBed.createComponent(SelectorModo);
    elegidos = [];
    fixture.componentInstance.elegir.subscribe((m) => elegidos.push(m));
    fixture.componentRef.setInput('modo', 'venta');
    fixture.detectChanges();
  });

  it('ofrece Venta, Presupuesto y Remito, en ese orden, dentro de un grupo con nombre', () => {
    expect(botones().map((b) => b.textContent!.trim())).toEqual(['Venta', 'Presupuesto', 'Remito']);
    expect(el().querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Qué querés hacer');
  });

  it('marca con aria-pressed solo el modo actual', () => {
    expect(boton('Venta').getAttribute('aria-pressed')).toBe('true');
    expect(boton('Presupuesto').getAttribute('aria-pressed')).toBe('false');
    expect(boton('Remito').getAttribute('aria-pressed')).toBe('false');

    fixture.componentRef.setInput('modo', 'remito');
    fixture.detectChanges();

    expect(boton('Venta').getAttribute('aria-pressed')).toBe('false');
    expect(boton('Remito').getAttribute('aria-pressed')).toBe('true');
  });

  it('emite el modo que se toca, también el que ya está elegido', () => {
    boton('Presupuesto').click();
    boton('Venta').click();

    expect(elegidos).toEqual(['presupuesto', 'venta']);
  });

  it('no le saca el foco a la caja de búsqueda al apretar el botón', () => {
    const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    boton('Remito').dispatchEvent(evento);

    expect(evento.defaultPrevented).toBe(true);
  });
});
