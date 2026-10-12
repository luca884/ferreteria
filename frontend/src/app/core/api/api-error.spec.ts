import { HttpErrorResponse } from '@angular/common/http';

import { erroresDeCampos } from './api-error';

describe('erroresDeCampos', () => {
  const http400 = (error: unknown) => new HttpErrorResponse({ status: 400, error });

  it('devuelve los mensajes de cada campo del 400 de validación, en orden', () => {
    const err = http400({
      status: 400,
      message: 'Datos invalidos',
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
