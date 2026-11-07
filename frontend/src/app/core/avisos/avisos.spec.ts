import { TestBed } from '@angular/core/testing';
import { Avisos } from './avisos';

describe('Avisos', () => {
  it('guarda un aviso para la próxima pantalla y lo entrega una sola vez', () => {
    const avisos = TestBed.inject(Avisos);

    avisos.dejar('Se guardó «Tornillo».');

    expect(avisos.tomar()).toEqual({ texto: 'Se guardó «Tornillo».', tipo: 'exito' });
    expect(avisos.tomar()).toBeNull();
  });

  it('un aviso de advertencia lleva su tipo', () => {
    const avisos = TestBed.inject(Avisos);

    avisos.dejar('No tenés acceso a Ventas.', 'advertencia');

    expect(avisos.tomar()).toEqual({ texto: 'No tenés acceso a Ventas.', tipo: 'advertencia' });
  });

  it('sin aviso pendiente devuelve null', () => {
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });
});
