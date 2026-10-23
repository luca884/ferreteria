import { formatearMonto } from '../../../core/formato/monto';
import { aCentavos, desdeTexto } from '../../mostrador/centavos';

/**
 * Contrato de la cobranza de cuenta corriente (CC-028..CC-039), tipado contra los DTO del backend: PendientesResponse,
 * CobranzaRequest, ReciboResponse y CobranzaRechazadaResponse. Todos los importes llegan en pesos con 2 decimales; las
 * cuentas se hacen en centavos enteros. Fechas: LocalDateTime ISO sin zona.
 */

/** Una cobranza se paga en efectivo o por transferencia: ni débito, ni crédito, ni cuenta corriente. */
export const MEDIOS_COBRANZA = ['EFECTIVO', 'TRANSFERENCIA'] as const;
export type MedioCobranza = (typeof MEDIOS_COBRANZA)[number];

export const MEDIO_COBRANZA_LABELS: Record<MedioCobranza, string> = {
  EFECTIVO: 'Efectivo',
  TRANSFERENCIA: 'Transferencia',
};

/**
 * Cómo se identifica una partida de la cuenta: una venta por `comprobanteId` o un Recargo por `ajusteId`, uno solo de los
 * dos (el otro es null o falta: un cliente viejo manda solo `comprobanteId`).
 */
export interface PartidaRef {
  comprobanteId?: number | null;
  ajusteId?: number | null;
}

/**
 * La clave de una partida para track-by, selección e imputación: `C-7` (venta) o `A-3` (Recargo). El número solo no
 * alcanza: el comprobante 3 y el ajuste 3 son partidas distintas.
 */
export function clavePartida(partida: PartidaRef): string {
  return partida.ajusteId != null ? `A-${partida.ajusteId}` : `C-${partida.comprobanteId}`;
}

/** Una partida con deuda: lo cargado a la cuenta, lo ya imputado y lo que queda por cobrar. */
export interface PendienteCuenta extends PartidaRef {
  /** Una venta; null en un Recargo. */
  comprobanteId: number | null;
  /** «Venta N.º 7» o «Recargo N.º 3»: copiada al asentar el movimiento. */
  referencia: string;
  fecha: string;
  importe: number;
  imputado: number;
  pendiente: number;
}

/**
 * Los pendientes de un cliente: saldo (negativo = a favor), lo que tiene «a cuenta» sin imputar y los comprobantes con
 * deuda en orden FIFO. Las tres cosas salen de la misma foto: Σ pendiente − aCuenta = saldo.
 */
export interface PendientesCuenta {
  saldo: number;
  aCuenta: number;
  comprobantes: PendienteCuenta[];
}

/** Cuánto del cobro (o del Descuento) se aplica a una partida: `comprobanteId` en una venta o `ajusteId` en un Recargo. */
export interface ImputacionCobro {
  comprobanteId?: number;
  ajusteId?: number;
  importe: number;
}

/** El renglón del pedido para una partida: solo la clave que corresponde, nunca las dos. */
export function imputacionDe(partida: PartidaRef, importe: number): ImputacionCobro {
  return partida.ajusteId != null
    ? { ajusteId: partida.ajusteId, importe }
    : { comprobanteId: partida.comprobanteId as number, importe };
}

/** POST /api/cobranzas. `imputaciones` vacía es válida: todo el total queda «a cuenta» (adelanto incluido). */
export interface CobranzaRequest {
  /** UUID generado al abrir el cobro: la misma clave en cada reintento de red. */
  clave: string;
  clienteId: number;
  medio: MedioCobranza;
  total: number;
  imputaciones: ImputacionCobro[];
}

export interface ReciboImputacion extends PartidaRef {
  /** Null cuando la imputación fue a un Recargo (`ajusteId`). */
  comprobanteId: number | null;
  referencia: string;
  importe: number;
}

/** El recibo de una cobranza: lo mismo en el 201, en el 200 de la repetida y en GET /recibos/{id}. */
export interface Recibo {
  id: number;
  numero: number;
  fecha: string;
  clienteId: number;
  clienteNombre: string;
  medio: MedioCobranza;
  total: number;
  /** Lo que quedó sin imputar: total − Σ imputaciones. */
  aCuenta: number;
  cobradoPor: string;
  /** Las imputaciones ORIGINALES: un recibo anulado conserva lo que cobró (las deudas ya volvieron a pendientes). */
  imputaciones: ReciboImputacion[];
  estado: EstadoRecibo;
  /** Quién, cuándo y por qué se anuló; los tres son null mientras el recibo está EMITIDO. */
  fechaAnulacion: string | null;
  anuladoPor: string | null;
  motivoAnulacion: string | null;
}

export type EstadoRecibo = 'EMITIDO' | 'ANULADO';

/** 201 si el cobro es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface ReciboCobrado {
  recibo: Recibo;
  repetida: boolean;
}

export type MotivoCobranza =
  | 'IMPUTACION_EXCEDE_DEUDA'
  | 'CAJA_CERRADA'
  | 'CAJA_DE_OTRO_DIA'
  | 'CLAVE_CON_OTRO_CONTENIDO'
  | 'RECIBO_YA_ANULADO';

/** La deuda ACTUAL de una partida en un 409, para refrescar la pantalla. */
export interface DeudaActual extends PartidaRef {
  comprobanteId: number | null;
  referencia: string;
  pendiente: number;
}

/** Cuerpo de los 409 y 422 con motivo. `comprobantes` viene solo en IMPUTACION_EXCEDE_DEUDA. */
export interface CobranzaRechazada {
  status: number;
  message: string;
  timestamp: string;
  motivo: MotivoCobranza;
  comprobantes?: DeudaActual[];
  /** Solo en RECIBO_YA_ANULADO: quién lo anuló y cuándo (fecha local ISO sin zona). */
  anuladoPor?: string;
  fechaAnulacion?: string;
}

/**
 * Los 400 de validación: `errors` por campo. «imputaciones» es el conjunto (comprobante repetido, Σ > total) e
 * «imputaciones[i].importe» o «imputaciones[i].comprobanteId», el renglón i del pedido.
 */
export interface CobranzaInvalida {
  status: number;
  message: string;
  errors?: Record<string, string>;
}

/** La sugerencia FIFO: un importe por pendiente (mismo orden, en centavos) y lo que queda «a cuenta». */
export interface SugerenciaFifo {
  imputaciones: number[];
  aCuentaCentavos: number;
}

/**
 * Reparte un total entre los pendientes en el orden en que vienen (FIFO: el backend ya los manda ordenados), en
 * centavos enteros: a cada comprobante le toca min(pendiente, lo que resta) y el sobrante queda «a cuenta». Sin
 * pendientes, todo es adelanto. Es solo una sugerencia: el backend valida y decide bajo lock.
 */
export function sugerirFifo(pendientes: readonly Pick<PendienteCuenta, 'pendiente'>[], totalCentavos: number): SugerenciaFifo {
  if (!Number.isSafeInteger(totalCentavos) || totalCentavos < 0) {
    throw new Error(`El total tiene que ser un entero de centavos: ${totalCentavos}`);
  }
  let resta = totalCentavos;
  const imputaciones = pendientes.map((p) => {
    const imputado = Math.max(0, Math.min(aCentavos(p.pendiente), resta));
    resta -= imputado;
    return imputado;
  });
  return { imputaciones, aCuentaCentavos: resta };
}

/** Lo que queda sin imputar: total − Σ imputado. Negativo si lo imputado se pasa del total. */
export function aCuentaCentavos(totalCentavos: number, imputadosCentavos: readonly number[]): number {
  return totalCentavos - imputadosCentavos.reduce((suma, c) => suma + c, 0);
}

/** Un importe escrito, en centavos; null si no es un número de hasta 2 decimales (o es negativo). */
export function leerImporte(texto: string): number | null {
  try {
    const centavos = desdeTexto(texto, 2);
    return centavos >= 0 ? centavos : null;
  } catch {
    return null;
  }
}

/** Una fila de la tabla de imputación: la partida con el texto que se ve, su importe en centavos y su error en línea. */
export interface FilaImputacion extends PendienteCuenta {
  /** `C-7` o `A-3`: lo que identifica la fila al editarla. */
  clave: string;
  /** Sufijo estable para los ids del DOM: `31` en una venta, `a3` en un Recargo. */
  idCampo: string;
  texto: string;
  centavos: number;
  error: string | null;
}

/**
 * Las filas de imputación de un cobro o de un Descuento: lo escrito a mano en cada partida (`editadas`, por clave) o, mientras
 * no se editó nada (`null`), la sugerencia FIFO (`sugeridas`, en centavos, en el mismo orden que `pendientes`). Una fila
 * se marca con error si no es un importe de hasta 2 decimales o si supera lo pendiente.
 */
export function filasDeImputacion(
  pendientes: readonly PendienteCuenta[],
  editadas: Readonly<Record<string, string>> | null,
  sugeridas: readonly number[],
): FilaImputacion[] {
  return pendientes.map((p, i) => {
    const clave = clavePartida(p);
    const texto = editadas ? (editadas[clave] ?? '') : sugeridas[i] === 0 ? '' : centavosATexto(sugeridas[i]);
    const centavos = texto.trim() === '' ? 0 : leerImporte(texto.trim());
    let error: string | null = null;
    if (centavos === null) error = MENSAJE_DECIMALES;
    else if (centavos > aCentavos(p.pendiente)) error = `No puede superar lo pendiente (${formatearMonto(p.pendiente)}).`;
    const idCampo = p.ajusteId != null ? `a${p.ajusteId}` : String(p.comprobanteId);
    return { ...p, clave, idCampo, texto, centavos: centavos ?? 0, error };
  });
}

/** Los renglones del pedido: solo las filas con importe, cada una con la clave de su partida. */
export function renglonesDeImputacion(filas: readonly FilaImputacion[]): ImputacionCobro[] {
  return filas.filter((f) => f.centavos > 0).map((f) => imputacionDe(f, f.centavos / 100));
}

/** Los centavos como lo que se escribe en un campo: «300,50» (coma decimal, sin separador de miles). */
export function centavosATexto(centavos: number): string {
  return `${Math.floor(centavos / 100)},${String(centavos % 100).padStart(2, '0')}`;
}

export const MENSAJE_DECIMALES = 'Usá un número con hasta 2 decimales.';

export const MOTIVO_MINIMO = 5;
export const MOTIVO_MAXIMO = 200;

/**
 * El error de un motivo de anulación (CC-056) o de un ajuste (`de` = «del ajuste»), o null si vale. La misma regla que el
 * backend: se recorta y se miden los caracteres que quedan, entre 5 y 200 (sin exigir letras: «12345» vale). El backend
 * vuelve a validar.
 */
export function validarMotivo(texto: string, de = 'de la anulación'): string | null {
  const largo = texto.trim().length;
  if (largo < MOTIVO_MINIMO) return `Escribí el motivo ${de} (al menos ${MOTIVO_MINIMO} letras).`;
  if (largo > MOTIVO_MAXIMO) return `El motivo admite hasta ${MOTIVO_MAXIMO} caracteres.`;
  return null;
}

/**
 * El día de hoy en Buenos Aires como «yyyy-MM-dd»: la caja se abre por día de esa zona, no del navegador. Sirve para saber
 * si un cobro es de hoy (una anulación de un cobro de otro día no cambia la caja de hoy).
 */
export function hoyEnBuenosAires(ahora: Date): string {
  const partes = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Argentina/Buenos_Aires',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(ahora);
  const valor = (tipo: string) => partes.find((p) => p.type === tipo)?.value ?? '';
  return `${valor('year')}-${valor('month')}-${valor('day')}`;
}

/** «19/10/2026 10:30» de una fecha local del servidor, leída del texto: no pasa por la zona del navegador. */
export function fechaCompletaLocal(fechaLocal: string): string {
  const [anio, mes, dia] = fechaLocal.slice(0, 10).split('-');
  return `${dia}/${mes}/${anio} ${fechaLocal.slice(11, 16)}`;
}
