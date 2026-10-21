import { HttpErrorResponse } from '@angular/common/http';

import { erroresDeCampos, esOcupado, mensajeDe } from './api-error';

describe('erroresDeCampos', () => {
  const http400 = (error: unknown) => new HttpErrorResponse({ status: 400, error });

  it('devuelve los mensajes de cada campo del 400 de validación, en orden', () => {
    const err = http400({
      status: 400,
      message: 'Datos inválidos',
      errors: { costo: 'El costo no puede ser negativo', descripcion: 'La descripción es obligatoria' },
    });

    expect(erroresDeCampos(err)).toEqual([
      'El costo no puede ser negativo',
      'La descripción es obligatoria',
    ]);
  });

  it('devuelve una lista vacía si no hay errors, está vacío o el error no es HTTP', () => {
    expect(erroresDeCampos(http400({ status: 409, message: 'Duplicado' }))).toEqual([]);
    expect(erroresDeCampos(http400({ message: 'x', errors: {} }))).toEqual([]);
    expect(erroresDeCampos(http400({ message: 'x', errors: null }))).toEqual([]);
    expect(erroresDeCampos(new HttpErrorResponse({ status: 0 }))).toEqual([]);
    expect(erroresDeCampos(new Error('boom'))).toEqual([]);
    expect(erroresDeCampos(undefined)).toEqual([]);
  });

  it('ignora los valores que no son texto o están en blanco', () => {
    const err = http400({ errors: { a: 'Mensaje válido', b: 7, c: '  ', d: null } });

    expect(erroresDeCampos(err)).toEqual(['Mensaje válido']);
  });
});

describe('mensajeDe', () => {
  it('devuelve el mensaje que manda el backend', () => {
    const err = new HttpErrorResponse({ status: 409, error: { message: 'La cotización está apagada' } });

    expect(mensajeDe(err, 'No se pudo recalcular.')).toBe('La cotización está apagada');
  });

  it('usa el mensaje por defecto si el error no es HTTP o no trae mensaje', () => {
    expect(mensajeDe(new HttpErrorResponse({ status: 0 }), 'No se pudo recalcular.')).toBe('No se pudo recalcular.');
    expect(mensajeDe(new HttpErrorResponse({ status: 500, error: { message: '' } }), 'Falló.')).toBe('Falló.');
    expect(mensajeDe(new Error('boom'), 'Falló.')).toBe('Falló.');
    expect(mensajeDe(undefined, 'Falló.')).toBe('Falló.');
  });
});

describe('esOcupado', () => {
  it('es verdadero solo para un 503 HTTP', () => {
    const err = new HttpErrorResponse({ status: 503, error: { message: 'El sistema está ocupado, reintentá.' } });

    expect(esOcupado(err)).toBe(true);
  });

  it('es falso para otros status, la red caída y errores que no son HTTP', () => {
    for (const status of [0, 400, 409, 500, 502]) {
      expect(esOcupado(new HttpErrorResponse({ status }))).toBe(false);
    }
    expect(esOcupado(new Error('x'))).toBe(false);
    expect(esOcupado(undefined)).toBe(false);
  });
});
