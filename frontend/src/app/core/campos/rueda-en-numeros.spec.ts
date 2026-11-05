import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { appConfig } from '../../app.config';

/** decisiones-pendientes S5: la rueda del mouse no cambia un campo numérico (un precio de 4.000 quedó en 3999,94). */
describe('rueda del mouse en los campos numéricos', () => {
  const campos: HTMLInputElement[] = [];

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    await TestBed.inject(ApplicationInitStatus).donePromise;
  });

  afterEach(() => campos.splice(0).forEach((c) => c.remove()));

  function campo(type: string): HTMLInputElement {
    const input = document.createElement('input');
    input.type = type;
    input.value = '4000';
    document.body.appendChild(input);
    campos.push(input);
    return input;
  }

  function rueda(destino: Element): WheelEvent {
    const evento = new WheelEvent('wheel', { deltaY: 100, bubbles: true, cancelable: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  it('sobre un campo numérico con el foco se cancela: el navegador no cambia el valor', () => {
    const precio = campo('number');
    precio.focus();

    expect(rueda(precio).defaultPrevented).toBe(true);
    expect(precio.value).toBe('4000');
  });

  it('sobre un campo numérico sin el foco no se toca: la página se desplaza como siempre', () => {
    const precio = campo('number');
    campo('text').focus();

    expect(rueda(precio).defaultPrevented).toBe(false);
  });

  it('sobre un campo de texto con el foco o sobre otro elemento no se toca', () => {
    const texto = campo('text');
    texto.focus();

    expect(rueda(texto).defaultPrevented).toBe(false);
    expect(rueda(document.body).defaultPrevented).toBe(false);
  });

  it('al destruirse la app deja de escuchar', () => {
    const precio = campo('number');
    precio.focus();

    TestBed.resetTestingModule();

    expect(rueda(precio).defaultPrevented).toBe(false);
  });
});
