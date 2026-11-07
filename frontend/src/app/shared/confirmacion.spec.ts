import { Component } from '@angular/core';
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

  it('en vuelo los dos botones quedan deshabilitados y Esc y Enter no hacen nada', async () => {
    await abrir({ titulo: '¿Anular el remito N.º 5?', textoConfirmar: 'Anular el remito', peligro: true, enVuelo: true });

    expect(boton('confirmar').disabled).toBe(true);
    expect(boton('cancelar').disabled).toBe(true);
    // Con los botones deshabilitados el foco no queda en ninguno: la tecla llega al diálogo.
    const dialogo = el().querySelector<HTMLElement>('[role="alertdialog"]')!;
    const enDialogo = (key: string) => {
      const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
      dialogo.dispatchEvent(evento);
      return evento;
    };
    const esc = enDialogo('Escape');
    const enter = enDialogo('Enter');
    expect(canceladas).toBe(0);
    expect(confirmadas).toBe(0);
    expect(esc.defaultPrevented).toBe(true);
    expect(enter.defaultPrevented).toBe(true);
  });

  it('sin enVuelo los botones arrancan habilitados (el valor por defecto)', async () => {
    await baja();

    expect(boton('confirmar').disabled).toBe(false);
    expect(boton('cancelar').disabled).toBe(false);
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

  describe('contenido proyectado', () => {
    @Component({
      imports: [Confirmacion],
      template: `
        <app-confirmacion titulo="¿Cambiar el redondeo?" mensaje="Se recalcula el precio de 3 artículos." textoConfirmar="Guardar">
          <table class="ejemplos"><tr><td>TOR-001</td></tr></table>
        </app-confirmacion>
      `,
    })
    class ConTabla {}

    @Component({
      imports: [Confirmacion],
      template: `<app-confirmacion titulo="¿Borrar?" mensaje="Se borra." textoConfirmar="Borrar" />`,
    })
    class SinContenido {}

    const hijosDelDialogo = (raiz: HTMLElement) =>
      [...raiz.querySelector('.dialogo')!.children].map((hijo) => hijo.tagName + (hijo.className ? '.' + hijo.className : ''));

    it('va entre el mensaje y los botones, y el foco y las teclas siguen igual', async () => {
      const host = TestBed.createComponent(ConTabla);
      host.detectChanges();
      await host.whenStable();
      const raiz = host.nativeElement as HTMLElement;
      const confirmacion = host.debugElement.children[0].componentInstance as Confirmacion;
      let canceladas = 0;
      confirmacion.cancelar.subscribe(() => canceladas++);

      expect(hijosDelDialogo(raiz)).toEqual(['H2', 'P', 'TABLE.ejemplos', 'DIV.dialogo-acciones']);
      expect(document.activeElement?.classList).toContain('cancelar');
      document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      expect(canceladas).toBe(1);
    });

    it('sin nada proyectado no agrega nada entre el mensaje y los botones', async () => {
      const host = TestBed.createComponent(SinContenido);
      host.detectChanges();
      await host.whenStable();

      expect(hijosDelDialogo(host.nativeElement as HTMLElement)).toEqual(['H2', 'P', 'DIV.dialogo-acciones']);
    });
  });
});
