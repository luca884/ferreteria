import { Articulo } from '../articulos/articulo.models';
import { ArticuloMostrador, comoArticuloMostrador, estadoStock } from './mostrador.models';

describe('estadoStock', () => {
  const con = (
    stockActual: number,
    stockMinimo: number,
  ): Pick<ArticuloMostrador, 'stockActual' | 'stockMinimo'> => ({
    stockActual,
    stockMinimo,
  });

  it('es sin-stock con stock 0 y mínimo 5', () => {
    expect(estadoStock(con(0, 5))).toBe('sin-stock');
  });

  it('es sin-stock con stock negativo', () => {
    expect(estadoStock(con(-1, 5))).toBe('sin-stock');
  });

  it('el rojo prevalece: stock 0 y mínimo 0 es sin-stock', () => {
    expect(estadoStock(con(0, 0))).toBe('sin-stock');
  });

  it('es bajo-minimo con stock 3 y mínimo 5', () => {
    expect(estadoStock(con(3, 5))).toBe('bajo-minimo');
  });

  it('con stock igual al mínimo no se marca', () => {
    expect(estadoStock(con(5, 5))).toBe('normal');
  });

  it('con stock por encima del mínimo es normal', () => {
    expect(estadoStock(con(6, 5))).toBe('normal');
  });

  it('con stock fraccionario positivo y mínimo 0 es normal', () => {
    expect(estadoStock(con(0.5, 0))).toBe('normal');
  });
});

describe('comoArticuloMostrador (medios-qa, S1)', () => {
  const ficha = (cambios: Partial<Articulo> = {}) =>
    ({
      id: 1,
      codigoInterno: 'COD-1',
      codigoBarras: null,
      codigoProveedor: null,
      descripcion: 'Martillo',
      precioFinal: 1800,
      unidad: 'UNIDAD',
      stockActual: 10,
      stockMinimo: 5,
      ubicacion: null,
      activo: true,
      ...cambios,
    }) as Articulo;

  it('con una oferta vigente, el precio que se cobra es el de oferta y el normal queda aparte', () => {
    const articulo = ficha({ oferta: { tipo: 'PRECIO_FIJO', valor: 1500, hasta: null, vigente: true, precioOferta: 1500 } });

    expect(comoArticuloMostrador(articulo)).toMatchObject({ precioFinal: 1500, enOferta: true, precioNormal: 1800 });
  });

  it('con una oferta vencida, queda el precio normal y sin oferta', () => {
    const articulo = ficha({ oferta: { tipo: 'PRECIO_FIJO', valor: 1500, hasta: '2026-01-31', vigente: false, precioOferta: null } });

    expect(comoArticuloMostrador(articulo)).toMatchObject({ precioFinal: 1800, enOferta: false, precioNormal: null });
  });
});
