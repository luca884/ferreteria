import { PresupuestoResponse } from '../presupuestos/presupuesto.models';
import { RemitoResponse } from '../remitos/remito.models';
import { ArticuloMostrador } from './mostrador.models';
import {
  Ticket,
  agregar,
  elegirCliente,
  quitarCliente,
  aPresupuestoRequest,
  aRemitoRequest,
  aRequest,
  actualizarPrecios,
  cambiarCantidad,
  descontarVendido,
  marcarProblemas,
  aplicarDescuentoGlobal,
  aplicarDescuentoRenglon,
  moverElegido,
  elegir,
  parsearCantidad,
  parsearDescuento,
  subtotalDelTicket,
  quitar,
  sinStock,
  soltarPresupuesto,
  soltarRemito,
  ticketDePresupuesto,
  ticketDeRemito,
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

  describe('elegir', () => {
    it('elige el renglón del índice', () => {
      expect(elegir(conTres(), 0).elegido).toBe(0);
    });

    it('un índice fuera del ticket no cambia nada', () => {
      const t = conTres();
      expect(elegir(t, 3)).toBe(t);
      expect(elegir(t, -1)).toBe(t);
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

    it('aRequest arma clave, renglones, totalMostrado y formaPagoId, sin recibido ni vuelto', () => {
      let t = agregar(ticketVacio('clave-1'), cable());
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, art());
      expect(aRequest(t, 2)).toEqual({
        clave: 'clave-1',
        renglones: [
          { articuloId: 2, cantidad: 2.5, precioUnitario: 790 },
          { articuloId: 1, cantidad: 1, precioUnitario: 170 },
        ],
        totalMostrado: 2145,
        formaPagoId: 2,
      });
    });

    it('aRequest con el recargo de la forma: el total mostrado lo incluye y los renglones no cambian (GDS-11)', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRequest(t, 3, 1700);

      expect(pedido.formaPagoId).toBe(3);
      expect(pedido.totalMostrado).toBe(187);
      expect(pedido.renglones).toEqual([{ articuloId: 1, cantidad: 1, precioUnitario: 170 }]);
    });

    it('aRequest con el descuento de la forma (recargo negativo): el total mostrado lo resta y los renglones no cambian', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRequest(t, 1, -1700);

      expect(pedido.totalMostrado).toBe(153);
      expect(pedido.renglones).toEqual([{ articuloId: 1, cantidad: 1, precioUnitario: 170 }]);
    });

    it('aRequest manda autorizaExceso true solo si se autoriza el exceso del límite (CC-013)', () => {
      const t = agregar(ticketVacio('c'), art());

      expect('autorizaExceso' in aRequest(t, 1)).toBe(false);
      expect('autorizaExceso' in aRequest(t, 1, 0, false, false)).toBe(false);
      expect(aRequest(t, 1, 0, false, true).autorizaExceso).toBe(true);
    });

    it('aRequest manda facturable false solo si la venta es no facturable (NF-003)', () => {
      const t = agregar(ticketVacio('c'), art());

      expect('facturable' in aRequest(t, 1)).toBe(false);
      expect('facturable' in aRequest(t, 1, 0, false)).toBe(false);
      expect(aRequest(t, 1, 0, true).facturable).toBe(false);
    });
  });

  describe('aPresupuestoRequest (GDS-13)', () => {
    it('arma clave, renglones, total sin recargo y los datos del diálogo; sin cliente ni observaciones no los manda', () => {
      let t = agregar(ticketVacio('clave-1'), cable());
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, art());

      expect(aPresupuestoRequest(t, { diasValidez: 15 })).toEqual({
        clave: 'clave-1',
        renglones: [
          { articuloId: 2, cantidad: 2.5, precioUnitario: 790 },
          { articuloId: 1, cantidad: 1, precioUnitario: 170 },
        ],
        totalMostrado: 2145,
        diasValidez: 15,
      });
    });

    it('manda el cliente y las observaciones, sin espacios de más', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aPresupuestoRequest(t, {
        diasValidez: 30,
        clienteNombre: '  Obra Perez ',
        observaciones: ' Entrega el viernes ',
      });

      expect(pedido.diasValidez).toBe(30);
      expect(pedido.clienteNombre).toBe('Obra Perez');
      expect(pedido.observaciones).toBe('Entrega el viernes');
    });

    it('un cliente o unas observaciones en blanco no viajan', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aPresupuestoRequest(t, { diasValidez: 15, clienteNombre: '   ', observaciones: '' });

      expect('clienteNombre' in pedido).toBe(false);
      expect('observaciones' in pedido).toBe(false);
    });

    it('manda los descuentos como la venta y el total mostrado los incluye', () => {
      const t = agregar(ticketVacio('c'), art());
      const con = aplicarDescuentoGlobal(aplicarDescuentoRenglon(t, 0, 1050).ticket!, 500).ticket!;

      const pedido = aPresupuestoRequest(con, { diasValidez: 15 });

      expect(pedido.renglones[0].descuentoPorcentaje).toBe(10.5);
      expect(pedido.descuentoPorcentaje).toBe(5);
      expect(pedido.totalMostrado).toBe(totalDelTicket(con) / 100);
    });

    it('no manda forma de pago ni recargo', () => {
      const pedido = aPresupuestoRequest(agregar(ticketVacio('c'), art()), { diasValidez: 15 }) as unknown as Record<
        string,
        unknown
      >;

      expect(pedido['formaPagoId']).toBeUndefined();
      expect(pedido['pagos']).toBeUndefined();
    });
  });

  describe('aRemitoRequest (G55)', () => {
    it('arma clave, renglones y total sin recargo; sin cliente ni observaciones no los manda', () => {
      let t = agregar(ticketVacio('clave-1'), cable());
      t = cambiarCantidad(t, 0, 2500).ticket!;
      t = agregar(t, art());

      expect(aRemitoRequest(t, {})).toEqual({
        clave: 'clave-1',
        renglones: [
          { articuloId: 2, cantidad: 2.5, precioUnitario: 790 },
          { articuloId: 1, cantidad: 1, precioUnitario: 170 },
        ],
        totalMostrado: 2145,
      });
    });

    it('manda el cliente y las observaciones, sin espacios de más', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRemitoRequest(t, { clienteNombre: '  Obra Perez ', observaciones: ' Entrega el viernes ' });

      expect(pedido.clienteNombre).toBe('Obra Perez');
      expect(pedido.observaciones).toBe('Entrega el viernes');
    });

    it('un cliente o unas observaciones en blanco no viajan', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRemitoRequest(t, { clienteNombre: '   ', observaciones: '' });

      expect('clienteNombre' in pedido).toBe(false);
      expect('observaciones' in pedido).toBe(false);
    });

    it('manda los descuentos como la venta y el total mostrado los incluye', () => {
      const t = agregar(ticketVacio('c'), art());
      const con = aplicarDescuentoGlobal(aplicarDescuentoRenglon(t, 0, 1050).ticket!, 500).ticket!;

      const pedido = aRemitoRequest(con, {});

      expect(pedido.renglones[0].descuentoPorcentaje).toBe(10.5);
      expect(pedido.descuentoPorcentaje).toBe(5);
      expect(pedido.totalMostrado).toBe(totalDelTicket(con) / 100);
    });

    it('no manda forma de pago, recargo ni validez', () => {
      const pedido = aRemitoRequest(agregar(ticketVacio('c'), art()), {}) as unknown as Record<string, unknown>;

      expect(pedido['formaPagoId']).toBeUndefined();
      expect(pedido['pagos']).toBeUndefined();
      expect(pedido['diasValidez']).toBeUndefined();
    });
  });

  describe('venta desde un presupuesto (GDS-13, PP-006)', () => {
    const renglon = (cambios: Partial<PresupuestoResponse['renglones'][number]> = {}) => ({
      renglon: 1,
      articuloId: 1,
      descripcion: 'Tornillo (del presupuesto)',
      unidad: 'UNIDAD' as const,
      cantidad: 2,
      precioUnitario: 100,
      alicuotaIva: 'IVA_21',
      bruto: 200,
      descuentoPorcentaje: 0,
      total: 200,
      neto: 165.29,
      iva: 34.71,
      ...cambios,
    });

    const presupuesto = (
      renglones: PresupuestoResponse['renglones'],
      cambios: Partial<PresupuestoResponse> = {},
    ): PresupuestoResponse => ({
      presupuestoId: 8,
      numero: 12,
      estado: 'PENDIENTE',
      fecha: '2026-10-18T10:00:00',
      vence: '2026-11-02',
      vencido: false,
      subtotal: 200,
      descuentoPorcentaje: 0,
      descuento: 0,
      neto: 0,
      iva: 0,
      total: 200,
      renglones,
      ...cambios,
    });

    it('aRequest manda el presupuesto del que sale la venta, y sin presupuesto no manda nada', () => {
      const t = agregar(ticketVacio('c'), art());
      expect('presupuestoId' in aRequest(t, 1)).toBe(false);

      const conPresupuesto = { ...t, presupuestoId: 8, presupuestoNumero: 12 };

      expect(aRequest(conPresupuesto, 1).presupuestoId).toBe(8);
    });

    it('el pedido del presupuesto no lleva el presupuesto: ya es uno', () => {
      const t = { ...agregar(ticketVacio('c'), art()), presupuestoId: 8, presupuestoNumero: 12 };

      expect('presupuestoId' in aPresupuestoRequest(t, { diasValidez: 15 })).toBe(false);
    });

    it('soltarPresupuesto lo saca del ticket y deja todo lo demás', () => {
      const t = { ...agregar(ticketVacio('c'), art()), presupuestoId: 8, presupuestoNumero: 12 };

      const suelto = soltarPresupuesto(t);

      expect(suelto.presupuestoId).toBeUndefined();
      expect(suelto.presupuestoNumero).toBeUndefined();
      expect(suelto.renglones).toEqual(t.renglones);
      expect(suelto.clave).toBe('c');
    });

    it('arma el ticket con las cantidades y los descuentos del presupuesto y los precios de hoy', () => {
      const p = presupuesto([renglon({ cantidad: 2.5, descuentoPorcentaje: 10.5 })], { descuentoPorcentaje: 5 });
      const actuales = new Map([[1, art({ descripcion: 'Tornillo', precioFinal: 120, stockActual: 7, stockMinimo: 2 })]]);

      const t = ticketDePresupuesto(p, actuales, 'clave-9');

      expect(t).toEqual({
        v: 1,
        clave: 'clave-9',
        presupuestoId: 8,
        presupuestoNumero: 12,
        descuentoGlobalBp: 500,
        elegido: 0,
        renglones: [
          {
            articuloId: 1,
            codigoInterno: 'TOR-001',
            descripcion: 'Tornillo',
            unidad: 'UNIDAD',
            precioCentavos: 12000,
            cantidadMilesimas: 2500,
            descuentoBp: 1050,
            stockActual: 7,
            stockMinimo: 2,
          },
        ],
      });
    });

    it('sin descuentos no los pone, y deja elegido el último renglón', () => {
      const p = presupuesto([renglon(), renglon({ renglon: 2, articuloId: 2 })]);
      const actuales = new Map([
        [1, art()],
        [2, cable()],
      ]);

      const t = ticketDePresupuesto(p, actuales, 'c');

      expect(t.descuentoGlobalBp).toBeUndefined();
      expect(t.renglones[0].descuentoBp).toBeUndefined();
      expect(t.elegido).toBe(1);
    });

    it('un artículo dado de baja, sin precio o que ya no existe queda en el ticket marcado, con el precio del presupuesto', () => {
      const p = presupuesto([
        renglon(),
        renglon({ renglon: 2, articuloId: 2, descripcion: 'Cable', precioUnitario: 790 }),
        renglon({ renglon: 3, articuloId: 3, descripcion: 'Martillo', precioUnitario: 1500 }),
        renglon({ renglon: 4, articuloId: 4, descripcion: 'Borrado', precioUnitario: 50 }),
      ]);
      const actuales = new Map<number, ArticuloMostrador | null>([
        [1, art()],
        [2, art({ id: 2, descripcion: 'Cable', activo: false })],
        [3, art({ id: 3, descripcion: 'Martillo', precioFinal: 0 })],
        [4, null],
      ]);

      const t = ticketDePresupuesto(p, actuales, 'c');

      expect(t.renglones.map((r) => r.problema)).toEqual([
        undefined,
        'Artículo dado de baja',
        'Sin precio',
        'Ya no está disponible',
      ]);
      expect(t.renglones[3]).toMatchObject({ descripcion: 'Borrado', precioCentavos: 5000, codigoInterno: '' });
      expect(t.elegido).toBe(1);
    });

    it('un artículo que no se pudo leer del todo (ni siquiera está en el mapa) también queda marcado', () => {
      const t = ticketDePresupuesto(presupuesto([renglon()]), new Map(), 'c');

      expect(t.renglones[0].problema).toBe('Ya no está disponible');
    });
  });

  describe('venta desde un remito (RM-006, RM-008)', () => {
    const renglon = (cambios: Partial<RemitoResponse['renglones'][number]> = {}) => ({
      renglon: 1,
      articuloId: 1,
      descripcion: 'Tornillo (del remito)',
      unidad: 'UNIDAD' as const,
      cantidad: 2,
      precioUnitario: 100,
      alicuotaIva: 'IVA_21',
      bruto: 200,
      descuentoPorcentaje: 0,
      total: 200,
      neto: 165.29,
      iva: 34.71,
      ...cambios,
    });

    const remito = (renglones: RemitoResponse['renglones'], cambios: Partial<RemitoResponse> = {}): RemitoResponse => ({
      remitoId: 8,
      numero: 5,
      estado: 'EMITIDO',
      fecha: '2026-10-18T10:00:00',
      subtotal: 200,
      descuentoPorcentaje: 0,
      descuento: 0,
      neto: 0,
      iva: 0,
      total: 200,
      renglones,
      ...cambios,
    });

    it('aRequest manda el remito del que sale la venta, y sin remito no manda nada', () => {
      const t = agregar(ticketVacio('c'), art());
      expect('remitoId' in aRequest(t, 1)).toBe(false);

      const conRemito = { ...t, remitoId: 8, remitoNumero: 5 };

      expect(aRequest(conRemito, 1).remitoId).toBe(8);
      expect('presupuestoId' in aRequest(conRemito, 1)).toBe(false);
    });

    it('el pedido de un remito o de un presupuesto no lleva el remito de origen', () => {
      const t = { ...agregar(ticketVacio('c'), art()), remitoId: 8, remitoNumero: 5 };

      expect('remitoId' in aRemitoRequest(t, {})).toBe(false);
      expect('remitoId' in aPresupuestoRequest(t, { diasValidez: 15 })).toBe(false);
    });

    it('soltarRemito lo saca del ticket y deja todo lo demás', () => {
      const t = { ...agregar(ticketVacio('c'), art()), remitoId: 8, remitoNumero: 5 };

      const suelto = soltarRemito(t);

      expect(suelto.remitoId).toBeUndefined();
      expect(suelto.remitoNumero).toBeUndefined();
      expect(suelto.renglones).toEqual(t.renglones);
      expect(suelto.clave).toBe('c');
    });

    it('arma el ticket con las cantidades y los descuentos del remito y los precios de hoy', () => {
      const r = remito([renglon({ cantidad: 2.5, descuentoPorcentaje: 10.5 })], { descuentoPorcentaje: 5 });
      const actuales = new Map([[1, art({ descripcion: 'Tornillo', precioFinal: 120, stockActual: 7, stockMinimo: 2 })]]);

      const t = ticketDeRemito(r, actuales, 'clave-9');

      expect(t).toEqual({
        v: 1,
        clave: 'clave-9',
        remitoId: 8,
        remitoNumero: 5,
        descuentoGlobalBp: 500,
        elegido: 0,
        renglones: [
          {
            articuloId: 1,
            codigoInterno: 'TOR-001',
            descripcion: 'Tornillo',
            unidad: 'UNIDAD',
            precioCentavos: 12000,
            cantidadMilesimas: 2500,
            descuentoBp: 1050,
            stockActual: 7,
            stockMinimo: 2,
          },
        ],
      });
    });

    it('un artículo dado de baja, sin precio o que ya no existe queda marcado, con el precio del remito', () => {
      const r = remito([
        renglon(),
        renglon({ renglon: 2, articuloId: 2, descripcion: 'Cable', precioUnitario: 790 }),
        renglon({ renglon: 3, articuloId: 3, descripcion: 'Martillo', precioUnitario: 1500 }),
        renglon({ renglon: 4, articuloId: 4, descripcion: 'Borrado', precioUnitario: 50 }),
      ]);
      const actuales = new Map<number, ArticuloMostrador | null>([
        [1, art()],
        [2, art({ id: 2, descripcion: 'Cable', activo: false })],
        [3, art({ id: 3, descripcion: 'Martillo', precioFinal: 0 })],
        [4, null],
      ]);

      const t = ticketDeRemito(r, actuales, 'c');

      expect(t.renglones.map((x) => x.problema)).toEqual([
        undefined,
        'Artículo dado de baja',
        'Sin precio',
        'Ya no está disponible',
      ]);
      expect(t.renglones[3]).toMatchObject({ descripcion: 'Borrado', precioCentavos: 5000, codigoInterno: '' });
      expect(t.elegido).toBe(1);
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
      expect(aRequest(t, 1).descuentoPorcentaje).toBeUndefined();
      expect(aRequest(t, 1).renglones[0].descuentoPorcentaje).toBeUndefined();

      const con = aplicarDescuentoGlobal(aplicarDescuentoRenglon(t, 0, 1050).ticket!, 500).ticket!;
      const pedido = aRequest(con, 1);

      expect(pedido.renglones[0].descuentoPorcentaje).toBe(10.5);
      expect(pedido.descuentoPorcentaje).toBe(5);
      expect(pedido.totalMostrado).toBe(totalDelTicket(con) / 100);
    });

    it('aRequest con un pago mixto manda la lista de pagos (las partes sin recargo) y no la forma única', () => {
      const t = agregar(ticketVacio('c'), art());

      const pedido = aRequest(
        t,
        [
          { formaPagoId: 1, centavos: 7000 },
          { formaPagoId: 3, centavos: 10000 },
        ],
        1000,
      );

      expect(pedido.formaPagoId).toBeUndefined();
      expect(pedido.pagos).toEqual([
        { formaPagoId: 1, monto: 70 },
        { formaPagoId: 3, monto: 100 },
      ]);
      expect(pedido.totalMostrado).toBe(180);
    });

    it('tras cobrar, lo que se agregó en el medio queda sin el descuento global de la venta enviada', () => {
      const t = aplicarDescuentoGlobal(agregar(ticketVacio('k'), art()), 1000).ticket!;
      const pedido = aRequest(t, 1);
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
      const r = descontarVendido(t, aRequest(t, 1), nueva);
      expect(r).toEqual(ticketVacio(''));
    });

    it('un renglón agregado después del request queda, con clave nueva', () => {
      const t = agregar(ticketVacio('k'), art());
      const pedido = aRequest(t, 1);
      const conEscaneo = agregar(t, martillo());

      const r = descontarVendido(conEscaneo, pedido, nueva);

      expect(r.clave).toBe('nueva');
      expect(r.renglones.map((x) => x.articuloId)).toEqual([3]);
      expect(r.renglones[0].cantidadMilesimas).toBe(1000);
      expect(r.elegido).toBe(0);
    });

    it('si a un renglón enviado se le sumó cantidad, queda la diferencia', () => {
      const t = agregar(agregar(ticketVacio('k'), art()), art());
      const pedido = aRequest(t, 1);
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
      const pedido = aRequest(t, 1);
      const conMas = cambiarCantidad(t, 0, 3700).ticket!;

      const r = descontarVendido(conMas, pedido, nueva);

      expect(r.renglones[0].cantidadMilesimas).toBe(1200);
    });

    it('conserva el resto de los renglones y la marca de problema', () => {
      const t = agregar(ticketVacio('k'), art());
      const pedido = aRequest(t, 1);
      const conOtro = marcarProblemas(agregar(t, martillo()), [{ articuloId: 3, mensaje: 'x' }]);

      const r = descontarVendido(conOtro, pedido, nueva);

      expect(r.renglones[0].problema).toBe('x');
    });

    it('no genera clave si no sobra nada', () => {
      const t = conTres();
      let llamadas = 0;
      descontarVendido(t, aRequest(t, 1), () => `c${++llamadas}`);
      expect(llamadas).toBe(0);
    });
  });
});

describe('ticket con cliente (CL-005)', () => {
  const norte = { id: 7, razonSocial: 'Ferretería Norte SRL' };
  const conUno = () => agregar(ticketVacio('c'), art());

  it('elegirCliente deja el id y el nombre en el ticket y no toca los renglones', () => {
    const t = elegirCliente(conUno(), norte);

    expect(t.clienteId).toBe(7);
    expect(t.clienteNombre).toBe('Ferretería Norte SRL');
    expect(t.renglones).toEqual(conUno().renglones);
    expect(t.clave).toBe('c');
  });

  it('elegir otro cliente reemplaza al anterior', () => {
    const t = elegirCliente(elegirCliente(conUno(), norte), { id: 9, razonSocial: 'Ana Gómez' });

    expect(t.clienteId).toBe(9);
    expect(t.clienteNombre).toBe('Ana Gómez');
  });

  it('quitarCliente vuelve a Consumidor Final: no quedan ni el id ni el nombre', () => {
    const t = quitarCliente(elegirCliente(conUno(), norte));

    expect('clienteId' in t).toBe(false);
    expect('clienteNombre' in t).toBe(false);
  });

  it('se puede elegir cliente con el ticket vacío (antes del primer renglón)', () => {
    const t = elegirCliente(ticketVacio(''), norte);

    expect(t.clienteId).toBe(7);
    expect(t.renglones).toEqual([]);
  });

  it('elegirCliente guarda su condición de IVA (para el rótulo del cobro); otro cliente sin condición no hereda la anterior, y quitarlo la saca', () => {
    const inscripto = elegirCliente(conUno(), { ...norte, condicionIva: 'RESPONSABLE_INSCRIPTO' });
    expect(inscripto.clienteCondicionIva).toBe('RESPONSABLE_INSCRIPTO');

    expect('clienteCondicionIva' in elegirCliente(inscripto, { id: 9, razonSocial: 'Ana Gómez' })).toBe(false);
    expect('clienteCondicionIva' in quitarCliente(inscripto)).toBe(false);
  });

  it('aRequest manda clienteId solo si hay cliente', () => {
    expect('clienteId' in aRequest(conUno(), 1)).toBe(false);
    expect(aRequest(elegirCliente(conUno(), norte), 1).clienteId).toBe(7);
  });

  it('aPresupuestoRequest y aRemitoRequest mandan el clienteId y NO el texto libre cuando hay cliente', () => {
    const t = elegirCliente(conUno(), norte);

    const presupuesto = aPresupuestoRequest(t, { diasValidez: 15, clienteNombre: 'escrito a mano' });
    const remito = aRemitoRequest(t, { clienteNombre: 'escrito a mano' });

    expect(presupuesto.clienteId).toBe(7);
    expect('clienteNombre' in presupuesto).toBe(false);
    expect(remito.clienteId).toBe(7);
    expect('clienteNombre' in remito).toBe(false);
  });

  it('sin cliente elegido, el presupuesto y el remito siguen mandando el texto libre', () => {
    const presupuesto = aPresupuestoRequest(conUno(), { diasValidez: 15, clienteNombre: ' Juan, el del taller ' });
    const remito = aRemitoRequest(conUno(), { clienteNombre: 'Juan' });

    expect('clienteId' in presupuesto).toBe(false);
    expect(presupuesto.clienteNombre).toBe('Juan, el del taller');
    expect('clienteId' in remito).toBe(false);
    expect(remito.clienteNombre).toBe('Juan');
  });

  it('el sobrante tras cobrar es una venta nueva: sin el cliente de la anterior', () => {
    let t = elegirCliente(conUno(), norte);
    t = agregar(t, cable());
    const pedido = aRequest({ ...t, renglones: [t.renglones[0]] }, 1);

    const resto = descontarVendido(t, pedido, () => 'nueva');

    expect(resto.renglones).toHaveLength(1);
    expect('clienteId' in resto).toBe(false);
    expect('clienteNombre' in resto).toBe(false);
  });

  it('soltar el presupuesto o el remito conserva al cliente', () => {
    const t = elegirCliente({ ...conUno(), presupuestoId: 8, presupuestoNumero: 12 }, norte);
    const r = elegirCliente({ ...conUno(), remitoId: 8, remitoNumero: 5 }, norte);

    expect(soltarPresupuesto(t).clienteId).toBe(7);
    expect(soltarRemito(r).clienteId).toBe(7);
  });

  it('la venta que sale de un presupuesto o de un remito trae su cliente al ticket, si lo tenía', () => {
    const sinRenglones: PresupuestoResponse['renglones'] = [];
    const base = {
      presupuestoId: 8, numero: 12, estado: 'PENDIENTE', fecha: '2026-10-18T10:00:00', vence: '2026-11-02',
      vencido: false, subtotal: 0, descuentoPorcentaje: 0, descuento: 0, neto: 0, iva: 0, total: 0,
      renglones: sinRenglones,
    } as PresupuestoResponse;

    const conCliente = ticketDePresupuesto({ ...base, clienteId: 7, clienteNombre: 'Ferretería Norte SRL' }, new Map(), 'k');
    const conTexto = ticketDePresupuesto({ ...base, clienteNombre: 'Juan, el del taller' }, new Map(), 'k');
    const remitoBase = {
      remitoId: 8, numero: 5, estado: 'EMITIDO', fecha: '2026-10-18T10:00:00', subtotal: 0, descuentoPorcentaje: 0,
      descuento: 0, neto: 0, iva: 0, total: 0, renglones: [],
    } as RemitoResponse;
    const remitoConCliente = ticketDeRemito({ ...remitoBase, clienteId: 7, clienteNombre: 'Ferretería Norte SRL' }, new Map(), 'k');

    expect(conCliente.clienteId).toBe(7);
    expect(conCliente.clienteNombre).toBe('Ferretería Norte SRL');
    expect('clienteId' in conTexto).toBe(false);
    expect(remitoConCliente.clienteId).toBe(7);
    expect(remitoConCliente.clienteNombre).toBe('Ferretería Norte SRL');
  });

  it('con la condición de IVA del cliente de hoy, el ticket del presupuesto y del remito la lleva; sin cliente, no', () => {
    const presupuesto = {
      presupuestoId: 8, numero: 12, estado: 'PENDIENTE', fecha: '2026-10-18T10:00:00', vence: '2026-11-02',
      vencido: false, subtotal: 0, descuentoPorcentaje: 0, descuento: 0, neto: 0, iva: 0, total: 0, renglones: [],
      clienteId: 7, clienteNombre: 'Ferretería Norte SRL',
    } as PresupuestoResponse;
    const remito = {
      remitoId: 8, numero: 5, estado: 'EMITIDO', fecha: '2026-10-18T10:00:00', subtotal: 0, descuentoPorcentaje: 0,
      descuento: 0, neto: 0, iva: 0, total: 0, renglones: [], clienteId: 7, clienteNombre: 'Ferretería Norte SRL',
    } as RemitoResponse;

    expect(ticketDePresupuesto(presupuesto, new Map(), 'k', 'RESPONSABLE_INSCRIPTO').clienteCondicionIva).toBe(
      'RESPONSABLE_INSCRIPTO',
    );
    expect(ticketDeRemito(remito, new Map(), 'k', 'MONOTRIBUTISTA').clienteCondicionIva).toBe('MONOTRIBUTISTA');
    expect(ticketDePresupuesto(presupuesto, new Map(), 'k').clienteCondicionIva).toBeUndefined();
    expect(
      ticketDePresupuesto({ ...presupuesto, clienteId: undefined, clienteNombre: 'Juan' }, new Map(), 'k', 'MONOTRIBUTISTA')
        .clienteCondicionIva,
    ).toBeUndefined();
  });
});
