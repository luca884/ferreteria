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

/** Lo que devuelve el backend, igual para Dueño y Vendedor (ambos ven costo y margen). */
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
}

/** Lo que responde el servidor (409) cuando otro cambio modificó el artículo después de cargarlo. */
export const MENSAJE_ARTICULO_MODIFICADO = 'Otro cambio modificó este artículo. Recargá y volvé a intentar.';

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
}

/** Un paso del desglose, en el orden en que se aplican; `importe` va a 4 decimales en los intermedios. */
export interface PasoDePrecio {
  tipo:
    | 'LISTA'
    | 'SIN_IVA'
    | 'BONIFICACION'
    | 'AJUSTE'
    | 'COSTO'
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
