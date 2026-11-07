import { Unidad } from '../articulos/articulo.models';
import { CondicionIva } from '../clientes/cliente.models';
import { aCentavos, aMilesimas, desdeTexto, subtotalTicket, totalTicket } from './centavos';
import { DatosPresupuesto, EmitirPresupuestoRequest, PresupuestoResponse } from '../presupuestos/presupuesto.models';
import { DatosRemito, EmitirRemitoRequest, RemitoResponse } from '../remitos/remito.models';
import { ArticuloMostrador } from './mostrador.models';
import { ConfirmarVentaRequest, PagoCobro, RenglonVentaRequest } from './venta.models';

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
  /**
   * Lo que entregó el remito del que sale la venta, en milésimas: ya salió del stock, así que el aviso de sin stock mira
   * solo lo que sale de más (como el servidor, spec «Sin doble descuento de stock»). Ausente si el renglón no viene de
   * un remito; un ticket guardado antes de este campo no lo tiene.
   */
  remitidoMilesimas?: number;
  /** Stock que se vio al agregar: solo para advertir, el servidor decide. */
  stockActual: number;
  stockMinimo: number;
  /** Motivo por el que el servidor rechazó este renglón (dado de baja, ya no existe). */
  problema?: string;
  /** El rechazo fue por el descuento del renglón (por ejemplo, el tope): cambiar un descuento lo deja viejo. */
  problemaDeDescuento?: true;
}

export interface Ticket {
  v: 1;
  clave: string;
  renglones: RenglonTicket[];
  elegido: number | null;
  /** Descuento global del ticket, en centésimas de punto porcentual; se aplica a cada renglón. Ausente es 0. */
  descuentoGlobalBp?: number;
  /** El presupuesto del que sale la venta (GDS-13): se manda al cobrar y lo deja FACTURADO. Ausente si no sale de uno. */
  presupuestoId?: number;
  /** Su número, para mostrar de dónde viene el ticket. Va siempre junto con `presupuestoId`. */
  presupuestoNumero?: number;
  /** El remito del que sale la venta (RM-006): se manda al cobrar, lo deja FACTURADO y concilia el stock. Nunca junto con un presupuesto. */
  remitoId?: number;
  /** Su número, para mostrar de dónde viene el ticket. Va siempre junto con `remitoId`. */
  remitoNumero?: number;
  /** El cliente elegido con F9 (CL-005): viaja con la venta, el presupuesto y el remito. Ausente es Consumidor Final. */
  clienteId?: number;
  /** Su razón social, para mostrarla en el mostrador. Va siempre junto con `clienteId`. */
  clienteNombre?: string;
  /**
   * Su condición frente al IVA al elegirlo, para anticipar la letra de la factura en el cobro. Solo con `clienteId`; un
   * ticket guardado antes no la tiene (el cobro dice solo «Factura»).
   */
  clienteCondicionIva?: CondicionIva;
}

export interface ResultadoCantidad {
  ticket?: Ticket;
  error?: string;
}

/** Por qué un renglón no se puede vender, como lo marca el ticket (lo mismo que dice el servidor al rechazarlo). */
export const PROBLEMA_DADO_DE_BAJA = 'Artículo dado de baja';
export const PROBLEMA_INEXISTENTE = 'Ya no está disponible';
export const PROBLEMA_SIN_PRECIO = 'Sin precio';

/**
 * El renglón sin la marca de un rechazo por su descuento (un 400 como «Tu tope de descuento es 10 %.»): cambiar el
 * descuento la deja vieja y el próximo cobro lo vuelve a validar. Las demás marcas quedan (baja, sin precio,
 * inexistente, cantidad), porque cambiar el descuento no las arregla. QA 05/11, A-5.
 */
function sinMarcaDeDescuento(r: RenglonTicket): RenglonTicket {
  if (!r.problemaDeDescuento) return r;
  const { problema: _marca, problemaDeDescuento: _deDescuento, ...renglon } = r;
  return renglon;
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

/**
 * El descuento que termina teniendo un renglón con su descuento propio y el general, en centésimas de punto: la misma
 * cuenta del servidor (`TopeDeDescuento.efectivo`), 100 − (100 − r)(100 − g)/100. Puede tener decimales.
 */
export function descuentoEfectivoBp(renglonBp: number, generalBp: number): number {
  return 10000 - ((10000 - renglonBp) * (10000 - generalBp)) / 10000;
}

/** Pone (o saca, con 0) el descuento propio de un renglón. */
export function aplicarDescuentoRenglon(t: Ticket, indice: number, bp: number): ResultadoCantidad {
  if (!t.renglones[indice]) return { error: 'Ese renglón no existe.' };
  if (!descuentoValido(bp)) return { error: MENSAJE_DESCUENTO };
  const renglones = t.renglones.map((r, j) => (j === indice ? { ...sinMarcaDeDescuento(r), descuentoBp: bp } : r));
  return { ticket: { ...t, renglones } };
}

/** Pone (o saca, con 0) el descuento global del ticket. */
export function aplicarDescuentoGlobal(t: Ticket, bp: number): ResultadoCantidad {
  if (!descuentoValido(bp)) return { error: MENSAJE_DESCUENTO };
  return { ticket: { ...t, descuentoGlobalBp: bp, renglones: t.renglones.map(sinMarcaDeDescuento) } };
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

/** Elige el renglón del índice (clic en la grilla). Un índice fuera del ticket no cambia nada. */
export function elegir(t: Ticket, indice: number): Ticket {
  if (indice < 0 || indice >= t.renglones.length) return t;
  return { ...t, elegido: indice };
}

/** ↑ (-1) y ↓ (+1) sin salir del rango. Sin elegido, parte del último. */
export function moverElegido(t: Ticket, delta: -1 | 1): Ticket {
  if (t.renglones.length === 0) return t;
  const base = t.elegido ?? t.renglones.length - 1;
  const elegido = Math.min(t.renglones.length - 1, Math.max(0, base + delta));
  return { ...t, elegido };
}

/**
 * Lo que sale en la venta supera el stock visto. Justo en 0 no advierte. En un renglón de un remito sale solo lo que
 * pasa de lo remitido; si no pasa, no sale nada y no advierte.
 */
export function sinStock(r: RenglonTicket): boolean {
  const sale = r.cantidadMilesimas - (r.remitidoMilesimas ?? 0);
  return sale > 0 && sale > aMilesimas(r.stockActual);
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

/** Por qué el servidor rechazó un renglón; `deDescuento` si el error vino de su descuento (por ejemplo, el tope). */
export interface ProblemaDeRenglon {
  articuloId: number;
  mensaje: string;
  deDescuento?: boolean;
}

/**
 * Marca los renglones rechazados y limpia las marcas anteriores del resto. Deja elegido el primer renglón
 * con problema, para que F10 / F3 actúen sobre el que hay que arreglar.
 */
export function marcarProblemas(t: Ticket, problemas: readonly ProblemaDeRenglon[]): Ticket {
  const porId = new Map(problemas.map((p) => [p.articuloId, p]));
  const renglones: RenglonTicket[] = t.renglones.map(({ problema: _anterior, problemaDeDescuento: _deDescuento, ...r }) => {
    const problema = porId.get(r.articuloId);
    if (problema === undefined) return r;
    return problema.deDescuento
      ? { ...r, problema: problema.mensaje, problemaDeDescuento: true }
      : { ...r, problema: problema.mensaje };
  });
  const primero = renglones.findIndex((r) => r.problema !== undefined);
  return { ...t, renglones, elegido: primero >= 0 ? primero : t.elegido };
}

/** Los renglones como viajan al servidor: cantidad y precio en unidades, y el descuento solo si hay. */
function renglonesRequest(t: Ticket): RenglonVentaRequest[] {
  return t.renglones.map((r) => ({
    articuloId: r.articuloId,
    cantidad: r.cantidadMilesimas / 1000,
    precioUnitario: r.precioCentavos / 100,
    ...(r.descuentoBp ? { descuentoPorcentaje: r.descuentoBp / 100 } : {}),
  }));
}

/**
 * Cuerpo del POST: con una forma única manda `formaPagoId`; con un pago mixto, la lista `pagos` (cada parte sin recargo)
 * y no `formaPagoId`. `recargoCentavos` es el de las formas de pago (GDS-11): el total mostrado lo incluye. El recibido y
 * el vuelto son del diálogo de cobro y no viajan. `noFacturable` manda `facturable: false` (NF-003); si no, no manda nada.
 * `autorizaExceso` manda `autorizaExceso: true` (CC-013, solo el Dueño); si no, no manda nada.
 */
export function aRequest(
  t: Ticket,
  pago: number | readonly PagoCobro[],
  recargoCentavos = 0,
  noFacturable = false,
  autorizaExceso = false,
): ConfirmarVentaRequest {
  const global = t.descuentoGlobalBp ?? 0;
  return {
    clave: t.clave,
    renglones: renglonesRequest(t),
    totalMostrado: (totalDelTicket(t) + recargoCentavos) / 100,
    ...(t.presupuestoId ? { presupuestoId: t.presupuestoId } : {}),
    ...(t.remitoId ? { remitoId: t.remitoId } : {}),
    ...(typeof pago === 'number'
      ? { formaPagoId: pago }
      : { pagos: pago.map((p) => ({ formaPagoId: p.formaPagoId, monto: p.centavos / 100 })) }),
    ...(global ? { descuentoPorcentaje: global / 100 } : {}),
    ...(noFacturable ? { facturable: false as const } : {}),
    ...(t.clienteId ? { clienteId: t.clienteId } : {}),
    ...(autorizaExceso ? { autorizaExceso: true as const } : {}),
  };
}

/** Elige el cliente del ticket (F9): reemplaza al anterior y no toca nada más. Sirve también con el ticket vacío. */
export function elegirCliente(
  t: Ticket,
  cliente: { id: number; razonSocial: string; condicionIva?: CondicionIva },
): Ticket {
  return {
    ...quitarCliente(t),
    clienteId: cliente.id,
    clienteNombre: cliente.razonSocial,
    ...(cliente.condicionIva ? { clienteCondicionIva: cliente.condicionIva } : {}),
  };
}

/** Vuelve a Consumidor Final. */
export function quitarCliente(t: Ticket): Ticket {
  const { clienteId: _id, clienteNombre: _nombre, clienteCondicionIva: _condicion, ...resto } = t;
  return resto;
}

/** Saca el presupuesto del ticket: la venta sigue como una venta común (el presupuesto no cambia). */
export function soltarPresupuesto(t: Ticket): Ticket {
  const { presupuestoId: _id, presupuestoNumero: _numero, ...resto } = t;
  return resto;
}

/** Saca el remito del ticket: la venta sigue como una venta común (el remito no cambia). */
export function soltarRemito(t: Ticket): Ticket {
  const { remitoId: _id, remitoNumero: _numero, ...resto } = t;
  const renglones = resto.renglones.map(({ remitidoMilesimas: _remitido, ...r }) => r);
  return { ...resto, renglones };
}

/** Lo que un renglón de un presupuesto o de un remito aporta al ticket de la venta que sale de él. */
interface RenglonDeOrigen {
  articuloId: number;
  descripcion: string;
  unidad: Unidad;
  cantidad: number;
  precioUnitario: number;
  descuentoPorcentaje: number;
}

/** Los renglones del ticket de una venta que sale de un documento: sus cantidades y descuentos, con los precios de HOY. */
function renglonesDeOrigen(
  origen: readonly RenglonDeOrigen[],
  actuales: ReadonlyMap<number, ArticuloMostrador | null>,
): RenglonTicket[] {
  return origen.map((r) => {
    const actual = actuales.get(r.articuloId) ?? null;
    const base = {
      articuloId: r.articuloId,
      cantidadMilesimas: Math.round(r.cantidad * 1000),
      ...(r.descuentoPorcentaje > 0 ? { descuentoBp: Math.round(r.descuentoPorcentaje * 100) } : {}),
    };
    if (!actual) {
      return {
        ...base,
        codigoInterno: '',
        descripcion: r.descripcion,
        unidad: r.unidad,
        precioCentavos: aCentavos(r.precioUnitario),
        stockActual: 0,
        stockMinimo: 0,
        problema: PROBLEMA_INEXISTENTE,
      };
    }
    const problema = !actual.activo ? PROBLEMA_DADO_DE_BAJA : actual.precioFinal <= 0 ? PROBLEMA_SIN_PRECIO : undefined;
    return {
      ...base,
      codigoInterno: actual.codigoInterno,
      descripcion: actual.descripcion,
      unidad: actual.unidad,
      precioCentavos: aCentavos(actual.precioFinal),
      stockActual: actual.stockActual,
      stockMinimo: actual.stockMinimo,
      ...(problema ? { problema } : {}),
    };
  });
}

/**
 * El ticket de una venta que sale de un presupuesto (PP-006, PP-008): las cantidades y los descuentos del
 * presupuesto, pero con el nombre, el precio y el stock de HOY de cada artículo (`actuales`, por id; null o ausente es
 * que ya no existe). El que ya no se puede vender (dado de baja, sin precio, borrado) no se pierde en silencio: queda
 * marcado, con el precio del presupuesto, para que el cajero lo vea y lo quite. Deja elegido el primero marcado.
 * `condicionIva` es la del cliente de HOY (se lee al pasar a venta): con ella el cobro sabe la letra de la factura. No va
 * en el presupuesto porque sería una foto vieja.
 */
export function ticketDePresupuesto(
  p: PresupuestoResponse,
  actuales: ReadonlyMap<number, ArticuloMostrador | null>,
  clave: string,
  condicionIva?: CondicionIva,
): Ticket {
  const renglones = renglonesDeOrigen(p.renglones, actuales);
  const primero = renglones.findIndex((r) => r.problema !== undefined);
  return {
    v: 1,
    clave,
    renglones,
    elegido: primero >= 0 ? primero : renglones.length - 1,
    ...(p.descuentoPorcentaje > 0 ? { descuentoGlobalBp: Math.round(p.descuentoPorcentaje * 100) } : {}),
    presupuestoId: p.presupuestoId,
    presupuestoNumero: p.numero,
    ...(p.clienteId && p.clienteNombre
      ? { clienteId: p.clienteId, clienteNombre: p.clienteNombre, ...(condicionIva ? { clienteCondicionIva: condicionIva } : {}) }
      : {}),
  };
}

/** El ticket de una venta que sale de un remito (RM-006, RM-008): igual que el del presupuesto, con el remito de origen. */
export function ticketDeRemito(
  r: RemitoResponse,
  actuales: ReadonlyMap<number, ArticuloMostrador | null>,
  clave: string,
  condicionIva?: CondicionIva,
): Ticket {
  // Un artículo no se repite en un remito: cada renglón del ticket es uno del remito, en el mismo orden.
  const renglones = renglonesDeOrigen(r.renglones, actuales).map((x, i) => ({
    ...x,
    remitidoMilesimas: Math.round(r.renglones[i].cantidad * 1000),
  }));
  const primero = renglones.findIndex((x) => x.problema !== undefined);
  return {
    v: 1,
    clave,
    renglones,
    elegido: primero >= 0 ? primero : renglones.length - 1,
    ...(r.descuentoPorcentaje > 0 ? { descuentoGlobalBp: Math.round(r.descuentoPorcentaje * 100) } : {}),
    remitoId: r.remitoId,
    remitoNumero: r.numero,
    ...(r.clienteId && r.clienteNombre
      ? { clienteId: r.clienteId, clienteNombre: r.clienteNombre, ...(condicionIva ? { clienteCondicionIva: condicionIva } : {}) }
      : {}),
  };
}

/**
 * Cuerpo del POST de un presupuesto (GDS-13): los renglones y los descuentos del ticket, el total sin recargo (no
 * hay forma de pago), la validez y, si se completaron, el cliente y las observaciones.
 */
export function aPresupuestoRequest(t: Ticket, datos: DatosPresupuesto): EmitirPresupuestoRequest {
  const global = t.descuentoGlobalBp ?? 0;
  const cliente = datos.clienteNombre?.trim();
  const observaciones = datos.observaciones?.trim();
  return {
    clave: t.clave,
    renglones: renglonesRequest(t),
    totalMostrado: totalDelTicket(t) / 100,
    ...(global ? { descuentoPorcentaje: global / 100 } : {}),
    diasValidez: datos.diasValidez,
    ...(t.clienteId ? { clienteId: t.clienteId } : cliente ? { clienteNombre: cliente } : {}),
    ...(observaciones ? { observaciones } : {}),
  };
}

/**
 * Cuerpo del POST de un remito (G55): los renglones y los descuentos del ticket, el total sin recargo (no hay forma de
 * pago) y, si se completaron, el cliente y las observaciones.
 */
export function aRemitoRequest(t: Ticket, datos: DatosRemito): EmitirRemitoRequest {
  const global = t.descuentoGlobalBp ?? 0;
  const cliente = datos.clienteNombre?.trim();
  const observaciones = datos.observaciones?.trim();
  return {
    clave: t.clave,
    renglones: renglonesRequest(t),
    totalMostrado: totalDelTicket(t) / 100,
    ...(global ? { descuentoPorcentaje: global / 100 } : {}),
    ...(t.clienteId ? { clienteId: t.clienteId } : cliente ? { clienteNombre: cliente } : {}),
    ...(observaciones ? { observaciones } : {}),
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
