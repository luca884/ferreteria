import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TrampaFoco } from './trampa-foco';

@Component({
  imports: [TrampaFoco],
  template: `
    <button id="abrir" type="button">Abrir</button>
    <button id="otro" type="button">Otro</button>
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

  it('con el diálogo cerrado Tab no hace nada', async () => {
    await cerrar();
    porId('otro').focus();

    expect(tab().defaultPrevented).toBe(false);
  });
});
