import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Confirmacion } from './confirmacion';

describe('Confirmacion', () => {
  let fixture: ComponentFixture<Confirmacion>;
  let confirmadas: number;
  let canceladas: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const boton = (clase: string) => el().querySelector(`button.${clase}`) as HTMLButtonElement;

  function tecla(key: string): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    (document.activeElement ?? el()).dispatchEvent(evento);
    return evento;
  }

  async function abrir(entradas: Record<string, unknown>) {
    await TestBed.configureTestingModule({ imports: [Confirmacion] }).compileComponents();
    fixture = TestBed.createComponent(Confirmacion);
    confirmadas = 0;
    canceladas = 0;
    for (const [nombre, valor] of Object.entries(entradas)) fixture.componentRef.setInput(nombre, valor);
    fixture.componentInstance.confirmar.subscribe(() => confirmadas++);
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const baja = () =>
    abrir({
      titulo: '¿Dar de baja a Sinteplast?',
      mensaje: 'Sus artículos conservan la referencia.',
      textoConfirmar: 'Dar de baja',
      peligro: true,
    });

  it('es un diálogo modal con título, mensaje y los dos botones', async () => {
    await baja();

    const dialogo = el().querySelector('[role="alertdialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(el().querySelector('h2')!.textContent).toContain('¿Dar de baja a Sinteplast?');
    expect(el().querySelector('p')!.textContent).toContain('Sus artículos conservan la referencia.');
    expect(boton('confirmar').textContent!.trim()).toBe('Dar de baja');
    expect(boton('confirmar').classList).toContain('peligro');
    expect(boton('cancelar').textContent!.trim()).toBe('Cancelar');
  });

  it('una acción que no es peligrosa usa el botón primario', async () => {
    await abrir({ titulo: '¿Guardar?', textoConfirmar: 'Guardar' });

    expect(boton('confirmar').classList).toContain('primario');
    expect(boton('confirmar').classList).not.toContain('peligro');
    expect(el().querySelector('p')).toBeNull();
  });

  it('por defecto el foco arranca en Cancelar (lo seguro) y Enter no confirma', async () => {
    await baja();

    expect(document.activeElement).toBe(boton('cancelar'));
    tecla('Enter');
    expect(canceladas).toBe(1);
    expect(confirmadas).toBe(0);
  });

  it('con foco en confirmar, Enter confirma', async () => {
    await abrir({ titulo: '¿Guardar?', textoConfirmar: 'Guardar', foco: 'confirmar' });

    expect(document.activeElement).toBe(boton('confirmar'));
    const evento = tecla('Enter');
    expect(confirmadas).toBe(1);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('Esc cancela', async () => {
    await baja();

    tecla('Escape');

    expect(canceladas).toBe(1);
    expect(confirmadas).toBe(0);
  });

  it('los botones confirman y cancelan', async () => {
    await baja();

    boton('confirmar').click();
    boton('cancelar').click();

    expect(confirmadas).toBe(1);
    expect(canceladas).toBe(1);
  });

  it('atrapa el foco: Tab en el último botón vuelve al primero', async () => {
    await baja();
    boton('confirmar').focus();

    const evento = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    boton('confirmar').dispatchEvent(evento);

    expect(document.activeElement).toBe(boton('cancelar'));
    expect(evento.defaultPrevented).toBe(true);
  });

  it('al cerrarse devuelve el foco al botón que lo abrió', async () => {
    const abridor = document.createElement('button');
    document.body.appendChild(abridor);
    abridor.focus();
    await baja();
    expect(document.activeElement).toBe(boton('cancelar'));

    fixture.destroy();

    expect(document.activeElement).toBe(abridor);
    abridor.remove();
  });

  it('se puede cambiar el texto de Cancelar', async () => {
    await abrir({ titulo: '¿Descartar?', textoConfirmar: 'Descartar', textoCancelar: 'Seguir editando' });

    expect(boton('cancelar').textContent!.trim()).toBe('Seguir editando');
  });
});
