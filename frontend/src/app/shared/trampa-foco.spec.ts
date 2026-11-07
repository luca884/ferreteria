import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TrampaFoco } from './trampa-foco';

@Component({
  imports: [TrampaFoco],
  template: `
    <button id="abrir" type="button">Abrir</button>
    <button id="otro" type="button">Otro</button>
    <main id="contenido" tabindex="-1"></main>
    @if (abierto()) {
      <div role="dialog" aria-modal="true" appTrampaFoco>
        <button id="primero" type="button">Primero</button>
        <button id="apagado" type="button" disabled>Apagado</button>
        <span id="radio-fuera" role="radio" tabindex="-1">Fuera del Tab</span>
        <input id="medio" />
        <button id="ultimo" type="button">Último</button>
      </div>
    }
  `,
})
class Anfitrion {
  readonly abierto = signal(false);
}

describe('TrampaFoco', () => {
  let fixture: ComponentFixture<Anfitrion>;
  const el = () => fixture.nativeElement as HTMLElement;
  const porId = (id: string) => el().querySelector(`#${id}`) as HTMLElement;

  function tab(shift = false): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: shift, bubbles: true, cancelable: true });
    (document.activeElement ?? document.body).dispatchEvent(evento);
    return evento;
  }

  async function abrir(): Promise<void> {
    fixture.componentInstance.abierto.set(true);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function cerrar(): Promise<void> {
    fixture.componentInstance.abierto.set(false);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Anfitrion] }).compileComponents();
    fixture = TestBed.createComponent(Anfitrion);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    porId('abrir').focus();
    await abrir();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('Tab en el último control vuelve al primero', () => {
    porId('ultimo').focus();

    const evento = tab();

    expect(document.activeElement).toBe(porId('primero'));
    expect(evento.defaultPrevented).toBe(true);
  });

  it('Shift+Tab en el primer control va al último', () => {
    porId('primero').focus();

    const evento = tab(true);

    expect(document.activeElement).toBe(porId('ultimo'));
    expect(evento.defaultPrevented).toBe(true);
  });

  it('en el medio deja que el navegador mueva el foco', () => {
    porId('medio').focus();

    expect(tab().defaultPrevented).toBe(false);
    expect(tab(true).defaultPrevented).toBe(false);
  });

  it('si el foco se escapó del diálogo, Tab lo trae al primer control y Shift+Tab al último', () => {
    porId('otro').focus();
    tab();
    expect(document.activeElement).toBe(porId('primero'));

    porId('otro').focus();
    tab(true);
    expect(document.activeElement).toBe(porId('ultimo'));
  });

  it('al cerrarse devuelve el foco a lo que lo tenía al abrirse', async () => {
    porId('medio').focus();

    await cerrar();

    expect(document.activeElement).toBe(porId('abrir'));
  });

  it('al cerrarse no le quita el foco a quien ya lo tomó (el buscador del mostrador)', async () => {
    porId('otro').focus();

    await cerrar();

    expect(document.activeElement).toBe(porId('otro'));
  });

  describe('abierto sin un elemento enfocado (F4 desde el body)', () => {
    beforeEach(async () => {
      await cerrar();
      (document.activeElement as HTMLElement).blur();
      expect(document.activeElement).toBe(document.body);
      await abrir();
    });

    it('al cerrarse manda el foco al contenido principal, no lo deja en el body', async () => {
      porId('medio').focus();

      await cerrar();

      expect(document.activeElement).toBe(porId('contenido'));
    });

    it('si alguien ya tomó el foco al cerrar, se respeta', async () => {
      porId('otro').focus();

      await cerrar();

      expect(document.activeElement).toBe(porId('otro'));
    });
  });

  it('si lo que tenía el foco al abrirse ya no está en el documento, el foco va al contenido principal', async () => {
    porId('medio').focus();
    porId('abrir').remove();

    await cerrar();

    expect(document.activeElement).toBe(porId('contenido'));
  });

  it('con el diálogo cerrado Tab no hace nada', async () => {
    await cerrar();
    porId('otro').focus();

    expect(tab().defaultPrevented).toBe(false);
  });
});

/** Un diálogo abierto desde otro (el detalle de una venta y, encima, Imprimir adentro o Anular al lado). */
@Component({
  imports: [TrampaFoco],
  template: `
    <div role="dialog" aria-modal="true" appTrampaFoco>
      <button id="detalle-primero" type="button">Cerrar</button>
      @if (adentro()) {
        <div role="dialog" aria-modal="true" appTrampaFoco>
          <button id="adentro-primero" type="button">Cerrar</button>
          <button id="adentro-ultimo" type="button">Imprimir</button>
        </div>
      }
      <button id="detalle-ultimo" type="button">Anular</button>
    </div>
    @if (alLado()) {
      <div role="dialog" aria-modal="true" appTrampaFoco>
        <button id="al-lado-primero" type="button">Cancelar</button>
        <button id="al-lado-ultimo" type="button">Anular</button>
      </div>
    }
  `,
})
class AnfitrionAnidado {
  readonly adentro = signal(false);
  readonly alLado = signal(false);
}

describe('TrampaFoco con un diálogo encima de otro', () => {
  let fixture: ComponentFixture<AnfitrionAnidado>;
  const porId = (id: string) => (fixture.nativeElement as HTMLElement).querySelector(`#${id}`) as HTMLElement;

  function tab(shift = false): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: shift, bubbles: true, cancelable: true });
    (document.activeElement ?? document.body).dispatchEvent(evento);
    return evento;
  }

  async function mostrar(cual: 'adentro' | 'alLado', visible: boolean): Promise<void> {
    fixture.componentInstance[cual].set(visible);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AnfitrionAnidado] }).compileComponents();
    fixture = TestBed.createComponent(AnfitrionAnidado);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    porId('detalle-primero').focus();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('con uno abierto adentro, Tab cicla solo en el de arriba', async () => {
    await mostrar('adentro', true);
    porId('adentro-ultimo').focus();

    tab();
    expect(document.activeElement).toBe(porId('adentro-primero'));

    tab(true);
    expect(document.activeElement).toBe(porId('adentro-ultimo'));
  });

  it('con uno abierto al lado, Tab cicla solo en el de arriba', async () => {
    await mostrar('alLado', true);
    porId('al-lado-ultimo').focus();

    tab();
    expect(document.activeElement).toBe(porId('al-lado-primero'));

    tab(true);
    expect(document.activeElement).toBe(porId('al-lado-ultimo'));
  });

  it('cuando se cierra el de arriba, el de abajo vuelve a atrapar el foco', async () => {
    porId('detalle-ultimo').focus();
    await mostrar('alLado', true);
    await mostrar('alLado', false);
    expect(document.activeElement).toBe(porId('detalle-ultimo'));

    tab();

    expect(document.activeElement).toBe(porId('detalle-primero'));
  });
});
