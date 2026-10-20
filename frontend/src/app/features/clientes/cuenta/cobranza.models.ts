import { aCentavos } from '../../mostrador/centavos';

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

/** Un comprobante con deuda: lo cargado a la cuenta, lo ya imputado y lo que queda por cobrar. */
export interface PendienteCuenta {
  comprobanteId: number;
  /** «Venta N.º 7»: copiada al asentar la venta. */
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

/** Cuánto del cobro se aplica a un comprobante. */
export interface ImputacionCobro {
  comprobanteId: number;
  importe: number;
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

export interface ReciboImputacion {
  comprobanteId: number;
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
  imputaciones: ReciboImputacion[];
}

/** 201 si el cobro es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface ReciboCobrado {
  recibo: Recibo;
  repetida: boolean;
}

export type MotivoCobranza = 'IMPUTACION_EXCEDE_DEUDA' | 'CAJA_CERRADA' | 'CAJA_DE_OTRO_DIA' | 'CLAVE_CON_OTRO_CONTENIDO';

/** La deuda ACTUAL de un comprobante en un 409, para refrescar la pantalla. */
export interface DeudaActual {
  comprobanteId: number;
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

/** Los centavos como lo que se escribe en un campo: «300,50» (coma decimal, sin separador de miles). */
export function centavosATexto(centavos: number): string {
  return `${Math.floor(centavos / 100)},${String(centavos % 100).padStart(2, '0')}`;
}

/** «19/10/2026 10:30» de una fecha local del servidor, leída del texto: no pasa por la zona del navegador. */
export function fechaCompletaLocal(fechaLocal: string): string {
  const [anio, mes, dia] = fechaLocal.slice(0, 10).split('-');
  return `${dia}/${mes}/${anio} ${fechaLocal.slice(11, 16)}`;
}
