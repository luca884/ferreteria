import { ArticuloMostrador } from './mostrador.models';
import {
  Ticket,
  agregar,
  aRequest,
  actualizarPrecios,
  cambiarCantidad,
  descontarVendido,
  marcarProblemas,
  aplicarDescuentoGlobal,
  aplicarDescuentoRenglon,
  moverElegido,
  parsearCantidad,
  parsearDescuento,
  subtotalDelTicket,
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
    activo: true,
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

    it('marcarProblemas elige el primer renglón con problema (H-06)', () => {
      const t = marcarProblemas(conTres(), [
        { articuloId: 3, mensaje: 'x' },
        { articuloId: 2, mensaje: 'y' },
      ]);
      expect(t.elegido).toBe(1);
    });

    it('marcarProblemas sin ningún renglón coincidente no mueve el elegido', () => {
      const antes = conTres();
      const t = marcarProblemas(antes, [{ articuloId: 99, mensaje: 'x' }]);
      expect(t.elegido).toBe(antes.elegido);
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

  describe('descuentos (GDS-12)', () => {
    it('parsearDescuento lee el % con coma o punto y hasta 2 decimales, en centésimas de punto', () => {
      expect(parsearDescuento('10')).toBe(1000);
      expect(parsearDescuento('10,5')).toBe(1050);
      expect(parsearDescuento(' 0.01 ')).toBe(1);
      expect(parsearDescuento('99,99')).toBe(9999);
    });

    it('parsearDescuento devuelve null si no es un número o tiene más de 2 decimales', () => {
      for (const texto of ['', 'abc', '10,555', '-5']) expect(parsearDescuento(texto)).toBeNull();
    });

    it('el descuento del renglón baja el total de ese renglón y deja el resto igual', () => {
      const r = aplicarDescuentoRenglon(conTres(), 0, 1000);

      expect(r.error).toBeUndefined();
      expect(r.ticket!.renglones[0].descuentoBp).toBe(1000);
      expect(totalDelTicket(r.ticket!)).toBe(15300 + 79000 + 150000);
      expect(subtotalDelTicket(r.ticket!)).toBe(17000 + 79000 + 150000);
    });

    it('un descuento de 0 lo saca', () => {
      const con = aplicarDescuentoRenglon(conTres(), 0, 1000).ticket!;

      const sin = aplicarDescuentoRenglon(con, 0, 0).ticket!;

      expect(sin.renglones[0].descuentoBp).toBe(0);
      expect(totalDelTicket(sin)).toBe(17000 + 79000 + 150000);
    });

    it('el descuento de un renglón fuera de 0 a 99,99 % o de un renglón que no existe da error', () => {
      expect(aplicarDescuentoRenglon(conTres(), 0, 10000).error).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
      expect(aplicarDescuentoRenglon(conTres(), 0, -1).error).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
      expect(aplicarDescuentoRenglon(conTres(), 0, 10.5).error).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
      expect(aplicarDescuentoRenglon(conTres(), 7, 1000).error).toBe('Ese renglón no existe.');
    });

    it('el descuento global se aplica a cada renglón y el total sigue siendo la suma', () => {
      const r = aplicarDescuentoGlobal(conTres(), 1000);

      expect(r.error).toBeUndefined();
      expect(r.ticket!.descuentoGlobalBp).toBe(1000);
      expect(totalDelTicket(r.ticket!)).toBe(15300 + 71100 + 135000);
    });

    it('el global fuera de rango da error y no cambia nada', () => {
      expect(aplicarDescuentoGlobal(conTres(), 10000).error).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
    });

    it('agregar otra unidad de un renglón con descuento conserva el descuento', () => {
      const con = aplicarDescuentoRenglon(conTres(), 0, 1000).ticket!;

      const mas = agregar(con, art());

      expect(mas.renglones[0].descuentoBp).toBe(1000);
      expect(mas.renglones[0].cantidadMilesimas).toBe(2000);
    });

    it('aRequest manda los descuentos solo si hay: sin descuentos el cuerpo es el de siempre', () => {
      const t = agregar(ticketVacio('c'), art());
      expect(aRequest(t, 'EFECTIVO').descuentoPorcentaje).toBeUndefined();
      expect(aRequest(t, 'EFECTIVO').renglones[0].descuentoPorcentaje).toBeUndefined();

      const con = aplicarDescuentoGlobal(aplicarDescuentoRenglon(t, 0, 1050).ticket!, 500).ticket!;
      const pedido = aRequest(con, 'EFECTIVO');

      expect(pedido.renglones[0].descuentoPorcentaje).toBe(10.5);
      expect(pedido.descuentoPorcentaje).toBe(5);
      expect(pedido.totalMostrado).toBe(totalDelTicket(con) / 100);
    });

    it('aRequest con un pago mixto manda la lista de pagos y no el medio único', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRequest(t, [
        { medioPago: 'EFECTIVO', centavos: 7000 },
        { medioPago: 'DEBITO', centavos: 10000 },
      ]);

      expect(pedido.medioPago).toBeUndefined();
      expect(pedido.pagos).toEqual([
        { medio: 'EFECTIVO', monto: 70 },
        { medio: 'DEBITO', monto: 100 },
      ]);
      expect(pedido.totalMostrado).toBe(170);
    });

    it('tras cobrar, lo que se agregó en el medio queda sin el descuento global de la venta enviada', () => {
      const t = aplicarDescuentoGlobal(agregar(ticketVacio('k'), art()), 1000).ticket!;
      const pedido = aRequest(t, 'EFECTIVO');
      const conEscaneo = agregar(t, martillo());

      const r = descontarVendido(conEscaneo, pedido, () => 'nueva');

      expect(r.renglones.map((x) => x.articuloId)).toEqual([3]);
      expect(r.descuentoGlobalBp ?? 0).toBe(0);
    });
  });

  describe('descontarVendido (W-1)', () => {
    const nueva = () => 'nueva';

    it('si se vendió todo, el ticket queda vacío y sin clave', () => {
      const t = conTres();
      const r = descontarVendido(t, aRequest(t, 'EFECTIVO'), nueva);
      expect(r).toEqual(ticketVacio(''));
    });

    it('un renglón agregado después del request queda, con clave nueva', () => {
      const t = agregar(ticketVacio('k'), art());
      const pedido = aRequest(t, 'EFECTIVO');
      const conEscaneo = agregar(t, martillo());

      const r = descontarVendido(conEscaneo, pedido, nueva);

      expect(r.clave).toBe('nueva');
      expect(r.renglones.map((x) => x.articuloId)).toEqual([3]);
      expect(r.renglones[0].cantidadMilesimas).toBe(1000);
      expect(r.elegido).toBe(0);
    });

    it('si a un renglón enviado se le sumó cantidad, queda la diferencia', () => {
      const t = agregar(agregar(ticketVacio('k'), art()), art());
      const pedido = aRequest(t, 'EFECTIVO');
      const conEscaneo = agregar(t, art());

      const r = descontarVendido(conEscaneo, pedido, nueva);

      expect(r.renglones).toHaveLength(1);
      expect(r.renglones[0].articuloId).toBe(1);
      expect(r.renglones[0].cantidadMilesimas).toBe(1000);
      expect(r.clave).toBe('nueva');
    });

    it('descuenta fraccionarios en milésimas exactas', () => {
      const base = agregar(ticketVacio('k'), cable());
      const t = cambiarCantidad(base, 0, 2500).ticket!;
      const pedido = aRequest(t, 'EFECTIVO');
      const conMas = cambiarCantidad(t, 0, 3700).ticket!;

      const r = descontarVendido(conMas, pedido, nueva);

      expect(r.renglones[0].cantidadMilesimas).toBe(1200);
    });

    it('conserva el resto de los renglones y la marca de problema', () => {
      const t = agregar(ticketVacio('k'), art());
      const pedido = aRequest(t, 'EFECTIVO');
      const conOtro = marcarProblemas(agregar(t, martillo()), [{ articuloId: 3, mensaje: 'x' }]);

      const r = descontarVendido(conOtro, pedido, nueva);

      expect(r.renglones[0].problema).toBe('x');
    });

    it('no genera clave si no sobra nada', () => {
      const t = conTres();
      let llamadas = 0;
      descontarVendido(t, aRequest(t, 'EFECTIVO'), () => `c${++llamadas}`);
      expect(llamadas).toBe(0);
    });
  });
});
