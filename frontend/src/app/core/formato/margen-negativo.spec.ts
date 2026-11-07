import datos from '../../../../../backend/src/test/resources/catalogo/casos-margen-negativo.json';
import { avisoMargenNegativo } from './margen-negativo';
import { Alicuota } from './precio-final';

/**
 * La ficha avisa el margen negativo mientras se escribe el precio manual (#26): tiene que dar el mismo texto que
 * MargenNegativo.java en las vistas previas. Los casos son los de
 * backend/src/test/resources/catalogo/casos-margen-negativo.json (CasosCompartidosMargenNegativoTest).
 */
describe('avisoMargenNegativo', () => {
  const sinEspaciosRaros = (texto: string | null) => (texto === null ? null : texto.replace(/\s+/g, ' '));

  it('el JSON compartido trae los casos', () => {
    expect(datos.casos.length).toBeGreaterThanOrEqual(12);
  });

  it.each(datos.casos.map((c) => [c.nombre, c] as const))('%s', (_nombre, caso) => {
    const recargos = 'recargos' in caso ? caso.recargos : undefined;
    const cotizacion = 'cotizacion' in caso ? caso.cotizacion : undefined;

    const aviso = avisoMargenNegativo(caso.costo, recargos, cotizacion, caso.alicuota as Alicuota, caso.precio);

    expect(sinEspaciosRaros(aviso)).toBe(caso.aviso);
  });

  it('sin un dato para la cuenta no avisa', () => {
    expect(avisoMargenNegativo(null, [], '1', 'IVA_21', '1000')).toBeNull();
    expect(avisoMargenNegativo('1200', [], '1', 'IVA_21', null)).toBeNull();
    expect(avisoMargenNegativo('1200', [], null, 'IVA_21', '1000')).toBeNull();
    expect(avisoMargenNegativo('1200', [{ porcentaje: 'x' }], '1', 'IVA_21', '1000')).toBeNull();
  });

  it('acepta números como los del formulario', () => {
    expect(sinEspaciosRaros(avisoMargenNegativo(1200, [{ porcentaje: 0 }], undefined, 'IVA_21', 1000))).toBe(
      'Margen negativo: el costo ($ 1.200,00) supera el precio sin IVA ($ 826,45). Revisá el precio manual.',
    );
  });
});
