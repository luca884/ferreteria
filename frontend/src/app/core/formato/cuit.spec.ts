import { FormControl } from '@angular/forms';
import { cuitConGuiones, normalizarCuit, validarCuit } from './cuit';

/** Los mismos casos que backend/src/test/java/com/ferreteria/catalogo/model/CuitTest.java. */
describe('CUIT', () => {
  it.each(['30123456781', '30-12345678-1', ' 30-12345678-1 ', '30 12345678 1', '30.12345678.1'])(
    'acepta «%s» y lo devuelve con guiones',
    (escrito) => {
      expect(normalizarCuit(escrito)).toEqual({ cuit: '30-12345678-1' });
    },
  );

  it('si el resto da 11 el verificador es 0', () => {
    expect(normalizarCuit('30876543210')).toEqual({ cuit: '30-87654321-0' });
  });

  it('una persona: 20-12345678-6', () => {
    expect(normalizarCuit('20-12345678-6')).toEqual({ cuit: '20-12345678-6' });
  });

  it('vacío es sin CUIT', () => {
    expect(normalizarCuit('   ')).toEqual({ cuit: null });
  });

  it.each(['123', '301234567812', '30-1234567-1', '30-12345678-X'])('«%s» no tiene 11 números', (escrito) => {
    expect(normalizarCuit(escrito)).toEqual({
      error: 'El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.',
    });
  });

  it.each(['30-12345678-9', '30-87654321-1', '20-00000001-0', '20-00000001-9'])(
    '«%s» tiene el verificador mal',
    (escrito) => {
      expect(normalizarCuit(escrito)).toEqual({
        error: 'El CUIT no es válido: revisá los números, el último no coincide.',
      });
    },
  );

  it.each([
    ['30999999995', '30-99999999-5'],
    ['30-99999999-5', '30-99999999-5'],
  ])('para mostrar, %s sale con guiones', (guardado, mostrado) => {
    expect(cuitConGuiones(guardado)).toBe(mostrado);
  });

  it('para mostrar, lo que no son 11 números sale tal cual', () => {
    expect(cuitConGuiones('3099')).toBe('3099');
  });

  it('el validador de formulario devuelve el mensaje o null', () => {
    expect(validarCuit(new FormControl('30-12345678-1'))).toBeNull();
    expect(validarCuit(new FormControl(''))).toBeNull();
    expect(validarCuit(new FormControl('30-12345678-9'))).toEqual({
      cuit: 'El CUIT no es válido: revisá los números, el último no coincide.',
    });
  });
});
