import { PageResponse } from '../../core/api/page.models';
import { formatearMonto } from '../../core/formato/monto';
import { exacto } from '../../core/formato/precio-final';

export const ALICUOTAS_IVA = ['IVA_0', 'IVA_2_5', 'IVA_5', 'IVA_10_5', 'IVA_21', 'IVA_27'] as const;
export type AlicuotaIva = (typeof ALICUOTAS_IVA)[number];

export const ALICUOTA_IVA_LABELS: Record<AlicuotaIva, string> = {
  IVA_0: '0 %',
  IVA_2_5: '2,5 %',
  IVA_5: '5 %',
  IVA_10_5: '10,5 %',
  IVA_21: '21 %',
  IVA_27: '27 %',
};

export const UNIDADES = ['UNIDAD', 'METRO', 'KILO', 'LITRO'] as const;
export type Unidad = (typeof UNIDADES)[number];

export const UNIDAD_LABELS: Record<Unidad, string> = {
  UNIDAD: 'Unidad',
  METRO: 'Metro',
  KILO: 'Kilo',
  LITRO: 'Litro',
};

/** La moneda en que se carga el costo de un artículo. El precio final siempre está en pesos. */
export const MONEDAS = ['ARS', 'USD'] as const;
export type Moneda = (typeof MONEDAS)[number];

export const MONEDA_LABELS: Record<Moneda, string> = {
  ARS: 'Pesos (ARS)',
  USD: 'Dólares (USD)',
};

/** La moneda del artículo; ARS si todavía no hay artículo (alta) o si por alguna razón no vino. */
export function monedaDe(articulo: Pick<Articulo, 'moneda'> | null | undefined): Moneda {
  return articulo?.moneda ?? 'ARS';
}

/** Un ajuste sobre el precio final puede ser un porcentaje o un monto en pesos (CU-14). */
export const TIPOS_AJUSTE_PRECIO = ['PORCENTAJE', 'MONTO'] as const;
export type TipoAjustePrecio = (typeof TIPOS_AJUSTE_PRECIO)[number];

export const TIPO_AJUSTE_PRECIO_LABELS: Record<TipoAjustePrecio, string> = {
  PORCENTAJE: '%',
  MONTO: '$',
};

/** Un ajuste sobre el precio final calculado (CU-14): nombre opcional, tipo y valor, positivo o negativo. */
export interface AjustePrecio {
  nombre: string | null;
  tipo: TipoAjustePrecio;
  valor: number;
}

export const MENSAJE_VALOR_AJUSTE_OBLIGATORIO = 'El valor del ajuste es obligatorio.';
export const MENSAJE_VALOR_AJUSTE_DECIMALES = 'El valor del ajuste admite hasta 2 decimales.';
export const MENSAJE_PORCENTAJE_AJUSTE = 'El porcentaje del ajuste tiene que estar entre -99,99 y 1.000.';

/** Lo mismo que valida el backend sobre el valor de un ajuste de precio; null si sirve. */
export function errorDeValorDeAjustePrecio(
  tipo: TipoAjustePrecio | null,
  valor: number | null | undefined,
): string | null {
  if (valor === null || valor === undefined) return MENSAJE_VALOR_AJUSTE_OBLIGATORIO;
  const decimal = exacto(valor);
  if (decimal && decimal.escala > 2) return MENSAJE_VALOR_AJUSTE_DECIMALES;
  if (tipo === 'PORCENTAJE' && (valor < -99.99 || valor > 1000)) return MENSAJE_PORCENTAJE_AJUSTE;
  return null;
}

const FORMATO_PORCENTAJE_AJUSTE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/** «Flete +10 %» o «Ajuste −$ 5,00»: el nombre (o «Ajuste»), el signo y el valor, para el desglose. */
export function textoDeAjustePrecio(ajuste: AjustePrecio): string {
  const nombre = ajuste.nombre?.trim() || 'Ajuste';
  const signo = ajuste.valor < 0 ? '\u2212' : '+';
  const magnitud = Math.abs(ajuste.valor);
  const valor =
    ajuste.tipo === 'PORCENTAJE'
      ? `${FORMATO_PORCENTAJE_AJUSTE.format(magnitud)} %`
      : formatearMonto(magnitud);
  return `${nombre} ${signo}${valor}`;
}

/** Un recargo con nombre sobre el costo (CU-13), por ejemplo «Embalaje y carga» 4,44 %; negativo es descuento. */
export interface RecargoCosto {
  nombre: string;
  porcentaje: number;
}

/** Lo que devuelve el backend, igual para Dueño y Vendedor (ambos ven costo y margen). */
/** La última compra de un artículo en su ficha: lleva al detalle de la compra. */
export interface UltimaCompra {
  compraId: number;
  /** «Factura A 00013-00605684». */
  comprobante: string;
  /** AAAA-MM-DD. */
  fecha: string;
}

export interface Articulo {
  id: number;
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  rubroId: number | null;
  proveedorId: number | null;
  /** Sin IVA, en la moneda del artículo. */
  costo: number;
  /** En qué moneda está el costo. */
  moneda: Moneda;
  /** Porcentaje propio; null hereda el del rubro o el de la ferretería. */
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  /** Lo calcula el backend, siempre en pesos; el cliente no lo manda. */
  precioFinal: number;
  unidad: Unidad;
  stockActual: number;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
  /**
   * Lo que sigue lo suma el corte de ajustes y es opcional: una respuesta de un servidor anterior no lo trae y no
   * tiene que romper la pantalla. Los ajustes y las bonificaciones son solo de lectura.
   */
  precioListaProveedor?: number | null;
  /** Descuento o recargo propio en %: negativo descuenta, positivo recarga; null es «sin ajuste». */
  ajuste1?: number | null;
  ajuste2?: number | null;
  ajuste3?: number | null;
  /** Descuentos del proveedor guardados con el artículo: positivo descuenta. */
  bonificacion1?: number | null;
  bonificacion2?: number | null;
  bonificacion3?: number | null;
  listaIncluyeIva?: boolean | null;
  /**
   * Bloqueo optimista: la versión del artículo que hay que mandar en el próximo guardado. Una respuesta de un
   * servidor anterior no la trae.
   */
  version?: number;
  /**
   * CU-14, solo para el Dueño (el Vendedor no los recibe): el precio lo escribió el Dueño y no sale de la fórmula, y
   * los ajustes sobre el precio final calculado.
   */
  precioManual?: boolean;
  ajustesPrecio?: AjustePrecio[];
  /** CU-13, solo para el Dueño (el Vendedor no lo recibe): los recargos del costo; el precio sale del costo real. */
  recargosCosto?: RecargoCosto[];
  /** CU-13 S5, solo para el Dueño (el Vendedor no lo recibe): la última compra del artículo; null si nunca se compró. */
  ultimaCompra?: UltimaCompra | null;
  /** GDS-05, para el Dueño y el Vendedor: desde cuándo está al día el costo (AAAA-MM-DD). */
  costoAl?: string | null;
  /** Ofertas (S3), para el Dueño y el Vendedor: la oferta guardada, vigente o vencida; null sin oferta. */
  oferta?: Oferta | null;
}

/** Ofertas (S1): cómo fija la oferta el precio de venta. */
export type TipoOferta = 'PRECIO_FIJO' | 'DESCUENTO_PORCENTAJE' | 'DESCUENTO_MONTO';

export const TIPOS_OFERTA: readonly TipoOferta[] = ['PRECIO_FIJO', 'DESCUENTO_PORCENTAJE', 'DESCUENTO_MONTO'];

export const TIPO_OFERTA_LABELS: Record<TipoOferta, string> = {
  PRECIO_FIJO: 'Precio fijo',
  DESCUENTO_PORCENTAJE: 'Descuento en %',
  DESCUENTO_MONTO: 'Descuento en $',
};

/**
 * La oferta guardada del artículo. `vigente` y `precioOferta` son los de hoy: vencida, o si ya no baja el precio
 * normal, vuelve con `vigente` false y sin precio.
 */
export interface Oferta {
  tipo: TipoOferta;
  valor: number;
  /** Último día de la oferta (AAAA-MM-DD), inclusive; null dura hasta que el Dueño la saca. */
  hasta: string | null;
  vigente: boolean;
  precioOferta: number | null;
}

export interface OfertaRequest {
  tipo: TipoOferta;
  valor: number;
  hasta: string | null;
  version?: number;
}

/**
 * El precio que deja la oferta sobre ese precio normal, con la misma regla que el backend (`Oferta.precioSobre`): el %
 * al centavo hacia arriba; null si no queda por encima de 0 ni por debajo del normal.
 */
export function precioDeOferta(precioNormal: number, tipo: TipoOferta, valor: number): number | null {
  const normalEnCentavos = Math.round(precioNormal * 100);
  let centavos: number;
  if (tipo === 'PRECIO_FIJO') {
    centavos = Math.round(valor * 100);
  } else if (tipo === 'DESCUENTO_MONTO') {
    centavos = normalEnCentavos - Math.round(valor * 100);
  } else {
    // centavos x (100 - %) con el % en centésimos, para no arrastrar errores de coma flotante.
    const restoEnCentesimos = 10000 - Math.round(valor * 100);
    centavos = Math.ceil((normalEnCentavos * restoEnCentesimos) / 10000);
  }
  return centavos > 0 && centavos < normalEnCentavos ? centavos / 100 : null;
}

/** Lo que responde el servidor (409) cuando otro cambio modificó el artículo después de cargarlo. */
export const MENSAJE_ARTICULO_MODIFICADO = 'Otro cambio modificó este artículo. Recargá y volvé a intentar.';

/** Lo que responde el servidor (400) cuando un PUT no trae la versión del artículo. */
export const MENSAJE_FALTA_VERSION = 'Falta la versión del artículo. Recargá y volvé a intentar.';

export const MOTIVOS_AJUSTE = ['INGRESO_MERCADERIA', 'ROTURA', 'CONTEO', 'OTRO'] as const;
export type MotivoAjuste = (typeof MOTIVOS_AJUSTE)[number];

export const MOTIVO_AJUSTE_LABELS: Record<MotivoAjuste, string> = {
  INGRESO_MERCADERIA: 'Ingreso de mercadería',
  ROTURA: 'Rotura',
  CONTEO: 'Conteo',
  OTRO: 'Otro',
};

export type ModoAjuste = 'STOCK_REAL' | 'CANTIDAD';

/**
 * Ajuste manual de stock (CU-09). STOCK_REAL manda el total contado; CANTIDAD manda lo que entra (+) o sale (-),
 * que el backend suma al stock vigente al confirmar.
 */
export type AjusteStockRequest = { motivo: MotivoAjuste; detalle: string | null } & (
  | { modo?: 'STOCK_REAL'; stockReal: number }
  | { modo: 'CANTIDAD'; cantidad: number }
);

export interface AjusteStockResponse {
  articuloId: number;
  stockAnterior: number;
  diferencia: number;
  stockActual: number;
}

/** Tipo de movimiento de stock (el mismo enum que el backend). */
/** NOTA_CREDITO: la nota de crédito o la anulación interna de una venta (CU-08), que repone lo vendido. */
export type TipoMovimientoStock = 'VENTA' | 'AJUSTE' | 'REMITO' | 'ANULACION_REMITO' | 'COMPRA' | 'NOTA_CREDITO';

/**
 * Un movimiento del artículo (G29): cantidad con signo (salida negativa) y el saldo que quedó después. El comprobante
 * viene armado del backend («Venta N.º 12», «Factura A 00003-00001234 · Proveedor»); un ajuste no tiene y muestra su motivo.
 */
export interface MovimientoStock {
  id: number;
  fecha: string;
  tipo: TipoMovimientoStock;
  cantidad: number;
  saldo: number;
  comprobante: string | null;
  usuario: string | null;
  motivo: string | null;
}

/** Filtro de los movimientos: días inclusive, como «aaaa-mm-dd». */
export interface FiltroMovimientos {
  page: number;
  desde?: string;
  hasta?: string;
}

/** Un cliente que compró el artículo (G29, Ctrl+A de GDS): sus ventas, cuánto llevó y la última vez (AAAA-MM-DD). */
export interface ClienteDelArticulo {
  clienteId: number;
  razonSocial: string;
  activo: boolean;
  compras: number;
  cantidad: number;
  ultimaCompra: string;
}

/** La página de clientes del artículo y cuántas ventas sin cliente (consumidor final) lo tuvieron. */
export interface ClientesDelArticulo extends PageResponse<ClienteDelArticulo> {
  ventasSinCliente: number;
}

export interface ConfiguracionPrecios {
  margenDefectoPorcentaje: number;
  redondeoMultiplo: number;
}

/** Alta y edición. No lleva stock actual ni precio final: los resuelve el backend. */
export interface ArticuloRequest {
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  rubroId: number | null;
  proveedorId: number | null;
  costo: number;
  /** Siempre se manda: el backend conserva la actual si falta, pero el formulario la elige explícitamente. Cambiarla no convierte el costo. */
  moneda: Moneda;
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  unidad: Unidad;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
  /** La versión que se leyó (solo en la edición; el alta no la manda). Si no coincide con la vigente, 409. */
  version?: number;
  /**
   * CU-14. `precioManual` true fija `precioFinal` a mano; false lo vuelve a calcular; sin el campo el backend
   * conserva la marca. `precioFinal` solo cuenta con `precioManual` true. `ajustesPrecio` null conserva los
   * guardados y una lista vacía los borra: el formulario siempre manda la lista.
   */
  precioManual?: boolean | null;
  precioFinal?: number | null;
  ajustesPrecio?: AjustePrecio[] | null;
  /** CU-13: los recargos del costo (hasta 5). null conserva los guardados y una lista vacía los borra: el formulario siempre manda la lista. */
  recargosCosto?: RecargoCosto[] | null;
}

/** Un paso del desglose, en el orden en que se aplican; `importe` va a 4 decimales en los intermedios. */
export interface PasoDePrecio {
  tipo:
    | 'LISTA'
    | 'SIN_IVA'
    | 'BONIFICACION'
    | 'AJUSTE'
    | 'COSTO'
    | 'RECARGO'
    | 'COSTO_REAL'
    | 'COTIZACION'
    | 'MARGEN'
    | 'IVA'
    | 'REDONDEO'
    | 'PRECIO_FINAL';
  porcentaje: number | null;
  importe: number;
}

export type OrigenMargen = 'PROPIO' | 'RUBRO' | 'FERRETERIA';

/**
 * De dónde sale el precio de un artículo (`GET /articulos/{id}/desglose`), para el Dueño y el Vendedor. Sin lista
 * (`tieneLista` false) el precio de lista, la base, los ajustes y la comparación vienen en null y `motivoSinLista`
 * explica por qué. Las bonificaciones son las guardadas en el artículo, no las del proveedor de hoy.
 */
export interface DesglosePrecio {
  articuloId: number;
  tieneLista: boolean;
  motivoSinLista: string | null;
  moneda: Moneda;
  precioLista: number | null;
  listaIncluyeIva: boolean | null;
  alicuotaIva: AlicuotaIva;
  bonificacion1: number | null;
  bonificacion2: number | null;
  bonificacion3: number | null;
  ajuste1: number | null;
  ajuste2: number | null;
  ajuste3: number | null;
  pasos: PasoDePrecio[];
  /** El costo que sale de la lista con la base guardada y los ajustes actuales; null sin lista. */
  costoDeLista: number | null;
  costoGuardado: number;
  /** Si el costo guardado es igual al de la lista; null sin lista. */
  coincide: boolean | null;
  margen: number;
  origenMargen: OrigenMargen;
  /** Pesos por dólar; null en un artículo en pesos o si todavía no hay cotización. */
  cotizacion: number | null;
  /** true: el artículo está en dólares y la cotización está apagada (se usa el último dólar). */
  cotizacionCongelada: boolean;
  multiplo: number | null;
  precioFinal: number;
  /** CU-14: el precio es manual (no sale de la fórmula) y los ajustes sobre el precio final calculado. */
  precioManual?: boolean;
  ajustesPrecio?: AjustePrecio[];
  /** CU-13: los recargos del costo, en el orden de los pasos RECARGO. */
  recargosCosto?: RecargoCosto[];
}

/**
 * Los tres descuentos o recargos de un artículo (`PUT /articulos/{id}/ajustes`). No lleva costo: el costo sale de la
 * lista. `confirmaPisarCosto` autoriza a pisar un costo cargado a mano o movido por una actualización masiva.
 */
export interface AjustesRequest {
  ajuste1: number | null;
  ajuste2: number | null;
  ajuste3: number | null;
  confirmaPisarCosto: boolean;
  /** La versión del artículo que se leyó: si no coincide con la vigente, 409. */
  version?: number;
}
