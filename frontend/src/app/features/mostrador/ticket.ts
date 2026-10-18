import { Unidad } from '../articulos/articulo.models';
import { aCentavos, aMilesimas, desdeTexto, subtotalTicket, totalTicket } from './centavos';
import { ArticuloMostrador } from './mostrador.models';
import { ConfirmarVentaRequest, PagoCobro } from './venta.models';

/**
 * El ticket en curso, como datos inmutables: toda función devuelve un ticket nuevo. Plata en centavos y
 * cantidades en milésimas (ver centavos.ts); el total es siempre la suma de los renglones.
 */

export interface RenglonTicket {
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  unidad: Unidad;
  precioCentavos: number;
  cantidadMilesimas: number;
  /** Descuento propio del renglón, en centésimas de punto porcentual (1000 = 10 %). Ausente es 0. */
  descuentoBp?: number;
  /** Stock que se vio al agregar: solo para advertir, el servidor decide. */
  stockActual: number;
  stockMinimo: number;
  /** Motivo por el que el servidor rechazó este renglón (dado de baja, ya no existe). */
  problema?: string;
}

export interface Ticket {
  v: 1;
  clave: string;
  renglones: RenglonTicket[];
  elegido: number | null;
  /** Descuento global del ticket, en centésimas de punto porcentual; se aplica a cada renglón. Ausente es 0. */
  descuentoGlobalBp?: number;
}

export interface ResultadoCantidad {
  ticket?: Ticket;
  error?: string;
}

const DESCUENTO_MAXIMO_BP = 9999;
const MENSAJE_DESCUENTO = 'El descuento tiene que estar entre 0 y 99,99 %.';

export function ticketVacio(clave: string): Ticket {
  return { v: 1, clave, renglones: [], elegido: null };
}

export function totalDelTicket(t: Ticket): number {
  return totalTicket(t.renglones, t.descuentoGlobalBp ?? 0);
}

/** La suma de los brutos, antes de descuentos. */
export function subtotalDelTicket(t: Ticket): number {
  return subtotalTicket(t.renglones);
}

function descuentoValido(bp: number): boolean {
  return Number.isInteger(bp) && bp >= 0 && bp <= DESCUENTO_MAXIMO_BP;
}

/** Texto del diálogo (coma o punto) a centésimas de punto porcentual; null si no es un número con hasta 2 decimales. */
export function parsearDescuento(texto: string): number | null {
  try {
    const bp = desdeTexto(texto, 2);
    return bp >= 0 ? bp : null;
  } catch {
    return null;
  }
}

/** Pone (o saca, con 0) el descuento propio de un renglón. */
export function aplicarDescuentoRenglon(t: Ticket, indice: number, bp: number): ResultadoCantidad {
  if (!t.renglones[indice]) return { error: 'Ese renglón no existe.' };
  if (!descuentoValido(bp)) return { error: MENSAJE_DESCUENTO };
  const renglones = t.renglones.map((r, j) => (j === indice ? { ...r, descuentoBp: bp } : r));
  return { ticket: { ...t, renglones } };
}

/** Pone (o saca, con 0) el descuento global del ticket. */
export function aplicarDescuentoGlobal(t: Ticket, bp: number): ResultadoCantidad {
  if (!descuentoValido(bp)) return { error: MENSAJE_DESCUENTO };
  return { ticket: { ...t, descuentoGlobalBp: bp } };
}

/** Suma 1 en el renglón del artículo (o lo crea) y lo deja elegido; refresca precio y stock vistos. */
export function agregar(t: Ticket, a: ArticuloMostrador): Ticket {
  const i = t.renglones.findIndex((r) => r.articuloId === a.id);
  const vistos = {
    descripcion: a.descripcion,
    codigoInterno: a.codigoInterno,
    precioCentavos: aCentavos(a.precioFinal),
    stockActual: a.stockActual,
    stockMinimo: a.stockMinimo,
  };
  if (i >= 0) {
    const renglones = t.renglones.map((r, j) =>
      j === i ? { ...r, ...vistos, cantidadMilesimas: r.cantidadMilesimas + 1000 } : r,
    );
    return { ...t, renglones, elegido: i };
  }
  const nuevo: RenglonTicket = {
    articuloId: a.id,
    unidad: a.unidad,
    cantidadMilesimas: 1000,
    ...vistos,
  };
  return { ...t, renglones: [...t.renglones, nuevo], elegido: t.renglones.length };
}

export function cambiarCantidad(t: Ticket, indice: number, milesimas: number): ResultadoCantidad {
  const r = t.renglones[indice];
  if (!r) return { error: 'Ese renglón no existe.' };
  if (!(milesimas > 0)) return { error: 'La cantidad tiene que ser mayor que 0.' };
  if (!Number.isInteger(milesimas)) return { error: 'Usá hasta 3 decimales.' };
  if (r.unidad === 'UNIDAD' && milesimas % 1000 !== 0) {
    return { error: 'Este artículo se vende por unidad: usá un número entero.' };
  }
  const renglones = t.renglones.map((x, j) => (j === indice ? { ...x, cantidadMilesimas: milesimas } : x));
  return { ticket: { ...t, renglones } };
}

/** Texto del diálogo (coma o punto) a milésimas; null si no es un número con hasta 3 decimales. */
export function parsearCantidad(texto: string): number | null {
  try {
    return desdeTexto(texto, 3);
  } catch {
    return null;
  }
}

export function quitar(t: Ticket, indice: number): Ticket {
  if (indice < 0 || indice >= t.renglones.length) return t;
  const renglones = t.renglones.filter((_, j) => j !== indice);
  if (renglones.length === 0) return { ...t, renglones, elegido: null };
  const actual = t.elegido ?? renglones.length - 1;
  const elegido =
    indice < actual ? actual - 1 : indice === actual ? Math.min(indice, renglones.length - 1) : actual;
  return { ...t, renglones, elegido };
}

/** ↑ (-1) y ↓ (+1) sin salir del rango. Sin elegido, parte del último. */
export function moverElegido(t: Ticket, delta: -1 | 1): Ticket {
  if (t.renglones.length === 0) return t;
  const base = t.elegido ?? t.renglones.length - 1;
  const elegido = Math.min(t.renglones.length - 1, Math.max(0, base + delta));
  return { ...t, elegido };
}

/** La cantidad supera el stock visto. Justo en 0 no advierte. */
export function sinStock(r: RenglonTicket): boolean {
  return r.cantidadMilesimas > aMilesimas(r.stockActual);
}

export function actualizarPrecios(
  t: Ticket,
  cambios: readonly { articuloId: number; precioActual: number }[],
): Ticket {
  const porId = new Map(cambios.map((c) => [c.articuloId, aCentavos(c.precioActual)]));
  const renglones = t.renglones.map((r) =>
    porId.has(r.articuloId) ? { ...r, precioCentavos: porId.get(r.articuloId)! } : r,
  );
  return { ...t, renglones };
}

/**
 * Marca los renglones rechazados y limpia las marcas anteriores del resto. Deja elegido el primer renglón
 * con problema, para que F10 / F3 actúen sobre el que hay que arreglar.
 */
export function marcarProblemas(
  t: Ticket,
  problemas: readonly { articuloId: number; mensaje: string }[],
): Ticket {
  const porId = new Map(problemas.map((p) => [p.articuloId, p.mensaje]));
  const renglones: RenglonTicket[] = t.renglones.map(({ problema: _anterior, ...r }) => {
    const mensaje = porId.get(r.articuloId);
    return mensaje === undefined ? r : { ...r, problema: mensaje };
  });
  const primero = renglones.findIndex((r) => r.problema !== undefined);
  return { ...t, renglones, elegido: primero >= 0 ? primero : t.elegido };
}

/**
 * Cuerpo del POST: con una forma única manda `formaPagoId`; con un pago mixto, la lista `pagos` (cada parte sin recargo)
 * y no `formaPagoId`. `recargoCentavos` es el de las formas de pago (GDS-11): el total mostrado lo incluye. El recibido y
 * el vuelto son del diálogo de cobro y no viajan.
 */
export function aRequest(
  t: Ticket,
  pago: number | readonly PagoCobro[],
  recargoCentavos = 0,
): ConfirmarVentaRequest {
  const global = t.descuentoGlobalBp ?? 0;
  return {
    clave: t.clave,
    renglones: t.renglones.map((r) => ({
      articuloId: r.articuloId,
      cantidad: r.cantidadMilesimas / 1000,
      precioUnitario: r.precioCentavos / 100,
      ...(r.descuentoBp ? { descuentoPorcentaje: r.descuentoBp / 100 } : {}),
    })),
    totalMostrado: (totalDelTicket(t) + recargoCentavos) / 100,
    ...(typeof pago === 'number'
      ? { formaPagoId: pago }
      : { pagos: pago.map((p) => ({ formaPagoId: p.formaPagoId, monto: p.centavos / 100 })) }),
    ...(global ? { descuentoPorcentaje: global / 100 } : {}),
  };
}

/**
 * Saca del ticket solo lo que viajó en el POST (por artículo y cantidad). Lo que se agregó mientras la venta
 * estaba en vuelo queda, con clave nueva porque la venta enviada ya se cerró. Sin sobrante: ticket vacío.
 */
export function descontarVendido(
  t: Ticket,
  pedido: ConfirmarVentaRequest,
  claveNueva: () => string,
): Ticket {
  const vendido = new Map<number, number>();
  for (const r of pedido.renglones) {
    vendido.set(r.articuloId, (vendido.get(r.articuloId) ?? 0) + Math.round(r.cantidad * 1000));
  }
  const renglones = t.renglones.flatMap((r) => {
    const resta = r.cantidadMilesimas - (vendido.get(r.articuloId) ?? 0);
    return resta > 0 ? [{ ...r, cantidadMilesimas: resta }] : [];
  });
  if (renglones.length === 0) return ticketVacio('');
  const elegidoId = t.elegido === null ? undefined : t.renglones[t.elegido]?.articuloId;
  const i = renglones.findIndex((r) => r.articuloId === elegidoId);
  return { v: 1, clave: claveNueva(), renglones, elegido: i >= 0 ? i : renglones.length - 1 };
}
