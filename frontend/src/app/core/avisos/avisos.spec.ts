import { TestBed } from '@angular/core/testing';
import { Avisos } from './avisos';

describe('Avisos', () => {
  it('guarda un aviso para la próxima pantalla y lo entrega una sola vez', () => {
    const avisos = TestBed.inject(Avisos);

    avisos.dejar('Se guardó «Tornillo».');

    expect(avisos.tomar()).toBe('Se guardó «Tornillo».');
    expect(avisos.tomar()).toBeNull();
  });

  it('sin aviso pendiente devuelve null', () => {
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });
});
