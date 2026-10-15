import { ArticuloMostrador } from './mostrador.models';
import {
  Ticket,
  agregar,
  aRequest,
  actualizarPrecios,
  cambiarCantidad,
  marcarProblemas,
  moverElegido,
  parsearCantidad,
  quitar,
  sinStock,
  ticketVacio,
  totalDelTicket,
} from './ticket';

function art(over: Partial<ArticuloMostrador> = {}): ArticuloMostrador {
  return {
    id: 1,
    codigoInterno: 'TOR-001',
    codigoBarras: '7790001',
    codigoProveedor: null,
    descripcion: 'Tornillo',
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 12,
    stockMinimo: 5,
    ubicacion: null,
    ...over,
  };
}

const cable = () => art({ id: 2, descripcion: 'Cable', unidad: 'METRO', precioFinal: 790, stockActual: 100 });
const martillo = () => art({ id: 3, descripcion: 'Martillo', precioFinal: 1500 });

function conTres(): Ticket {
  return agregar(agregar(agregar(ticketVacio('k'), art()), cable()), martillo());
}

describe('ticket (puro)', () => {
  describe('agregar', () => {
    it('crea el renglón con 1 unidad y lo deja elegido', () => {
      const t = agregar(ticketVacio('k'), art());
      expect(t.renglones).toHaveLength(1);
      expect(t.renglones[0]).toMatchObject({
        articuloId: 1,
        descripcion: 'Tornillo',
        precioCentavos: 17000,
        cantidadMilesimas: 1000,
        stockActual: 12,
        stockMinimo: 5,
        unidad: 'UNIDAD',
      });
      expect(t.elegido).toBe(0);
    });

    it('el mismo artículo suma 1 en el mismo renglón y lo deja elegido', () => {
      let t = agregar(ticketVacio('k'), art());
      t = agregar(t, cable());
      t = agregar(t, art());
      expect(t.renglones).toHaveLength(2);
      expect(t.renglones[0].cantidadMilesimas).toBe(2000);
      expect(t.elegido).toBe(0);
    });

    it('con decimales: 2,5 pasa a 3,5', () => {
      let t = agregar(ticketVacio('k'), cable());
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, cable());
      expect(t.renglones[0].cantidadMilesimas).toBe(3500);
    });

    it('actualiza precio y stock con los que vio por última vez', () => {
      let t = agregar(ticketVacio('k'), art());
      t = agregar(t, art({ precioFinal: 180, stockActual: 9 }));
      expect(t.renglones[0].precioCentavos).toBe(18000);
      expect(t.renglones[0].stockActual).toBe(9);
    });

    it('artículos distintos quedan en orden de llegada', () => {
      const t = conTres();
      expect(t.renglones.map((r) => r.articuloId)).toEqual([1, 2, 3]);
      expect(t.elegido).toBe(2);
    });

    it('no muta el ticket original', () => {
      const t = agregar(ticketVacio('k'), art());
      agregar(t, art());
      expect(t.renglones[0].cantidadMilesimas).toBe(1000);
    });
  });

  describe('cambiarCantidad', () => {
    it('acepta una cantidad válida', () => {
      const r = cambiarCantidad(conTres(), 1, 2500);
      expect(r.error).toBeUndefined();
      expect(r.ticket!.renglones[1].cantidadMilesimas).toBe(2500);
    });

    it('rechaza 0 y negativos', () => {
      expect(cambiarCantidad(conTres(), 0, 0).error).toBe('La cantidad tiene que ser mayor que 0.');
      expect(cambiarCantidad(conTres(), 0, -1000).error).toBe('La cantidad tiene que ser mayor que 0.');
    });

    it('UNIDAD tiene que ser entera', () => {
      const r = cambiarCantidad(conTres(), 0, 2500);
      expect(r.error).toBe('Este artículo se vende por unidad: usá un número entero.');
      expect(r.ticket).toBeUndefined();
    });

    it('rechaza lo que no es múltiplo de una milésima', () => {
      expect(cambiarCantidad(conTres(), 1, 1500.5).error).toBe('Usá hasta 3 decimales.');
    });

    it('un índice inexistente no cambia nada', () => {
      expect(cambiarCantidad(conTres(), 9, 1000).error).toBeDefined();
    });
  });

  describe('parsearCantidad', () => {
    it('acepta coma o punto', () => {
      expect(parsearCantidad('2,5')).toBe(2500);
      expect(parsearCantidad(' 2.5 ')).toBe(2500);
      expect(parsearCantidad('3')).toBe(3000);
      expect(parsearCantidad('0,001')).toBe(1);
    });

    it('más de 3 decimales o texto inválido da null', () => {
      expect(parsearCantidad('0,0015')).toBeNull();
      expect(parsearCantidad('abc')).toBeNull();
      expect(parsearCantidad('')).toBeNull();
    });
  });

  describe('quitar y moverElegido', () => {
    it('quitar el elegido deja elegido el que ocupa su lugar', () => {
      const t = quitar(moverElegido(conTres(), -1), 1);
      expect(t.renglones.map((r) => r.articuloId)).toEqual([1, 3]);
      expect(t.elegido).toBe(1);
    });

    it('quitar el último elegido elige el nuevo último', () => {
      const t = quitar(conTres(), 2);
      expect(t.elegido).toBe(1);
    });

    it('quitar uno anterior al elegido corre el índice', () => {
      const t = quitar(conTres(), 0);
      expect(t.elegido).toBe(1);
      expect(t.renglones[t.elegido!].articuloId).toBe(3);
    });

    it('quitar el único renglón deja elegido null', () => {
      const t = quitar(agregar(ticketVacio('k'), art()), 0);
      expect(t.renglones).toHaveLength(0);
      expect(t.elegido).toBeNull();
    });

    it('moverElegido va con ↑ (-1) y ↓ (+1) sin salir del rango', () => {
      let t = conTres();
      t = moverElegido(t, 1);
      expect(t.elegido).toBe(2);
      t = moverElegido(t, -1);
      expect(t.elegido).toBe(1);
      t = moverElegido(moverElegido(t, -1), -1);
      expect(t.elegido).toBe(0);
    });

    it('moverElegido en un ticket vacío no hace nada', () => {
      expect(moverElegido(ticketVacio('k'), 1).elegido).toBeNull();
    });
  });

  describe('sinStock', () => {
    it('avisa cuando la cantidad supera el stock que se vio', () => {
      const t = cambiarCantidad(conTres(), 0, 13000).ticket!;
      expect(sinStock(t.renglones[0])).toBe(true);
    });

    it('justo en el stock no avisa', () => {
      const t = cambiarCantidad(conTres(), 0, 12000).ticket!;
      expect(sinStock(t.renglones[0])).toBe(false);
    });

    it('con stock 0, una unidad avisa', () => {
      const t = agregar(ticketVacio('k'), art({ stockActual: 0 }));
      expect(sinStock(t.renglones[0])).toBe(true);
    });

    it('compara decimales sin error de float', () => {
      const t = agregar(ticketVacio('k'), art({ unidad: 'KILO', stockActual: 0.3 }));
      const r = cambiarCantidad(t, 0, 300).ticket!;
      expect(sinStock(r.renglones[0])).toBe(false);
    });
  });

  describe('actualizarPrecios y marcarProblemas', () => {
    it('actualizarPrecios cambia solo los artículos indicados', () => {
      const t = actualizarPrecios(conTres(), [{ articuloId: 3, precioActual: 1650 }]);
      expect(t.renglones.map((r) => r.precioCentavos)).toEqual([17000, 79000, 165000]);
    });

    it('marcarProblemas pone el mensaje en esos renglones y limpia el resto', () => {
      let t = marcarProblemas(conTres(), [{ articuloId: 2, mensaje: 'Dado de baja' }]);
      expect(t.renglones.map((r) => r.problema)).toEqual([undefined, 'Dado de baja', undefined]);
      t = marcarProblemas(t, [{ articuloId: 1, mensaje: 'Ya no existe' }]);
      expect(t.renglones.map((r) => r.problema)).toEqual(['Ya no existe', undefined, undefined]);
    });

    it('quitar un renglón con problema no deja marcas en los demás', () => {
      const t = quitar(marcarProblemas(conTres(), [{ articuloId: 2, mensaje: 'x' }]), 1);
      expect(t.renglones.every((r) => r.problema === undefined)).toBe(true);
    });
  });

  describe('total y aRequest', () => {
    it('el total es la suma de los renglones en centavos', () => {
      let t = agregar(ticketVacio('k'), art({ precioFinal: 333.33, unidad: 'METRO' }));
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, art({ id: 9, precioFinal: 1210 }));
      expect(totalDelTicket(t)).toBe(83333 + 121000);
    });

    it('aRequest arma clave, renglones, totalMostrado y medioPago, sin recibido ni vuelto', () => {
      let t = agregar(ticketVacio('clave-1'), cable());
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, art());
      expect(aRequest(t, 'DEBITO')).toEqual({
        clave: 'clave-1',
        renglones: [
          { articuloId: 2, cantidad: 2.5, precioUnitario: 790 },
          { articuloId: 1, cantidad: 1, precioUnitario: 170 },
        ],
        totalMostrado: 2145,
        medioPago: 'DEBITO',
      });
    });
  });
});
