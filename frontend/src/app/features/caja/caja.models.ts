import { formatearMonto } from '../../core/formato/monto';
import { MEDIOS_PAGO, MedioPago } from '../mostrador/venta.models';

/** Contrato de /api/cajas (CU-10), tipado contra CajaResponses del backend. Fechas: LocalDateTime ISO sin zona. */

/** La caja de la ferretería vista desde cualquier rol: sin totales (cierre ciego). */
export type EstadoCajaVigente = 'SIN_CAJA' | 'ABIERTA' | 'DE_OTRO_DIA';
export type EstadoCaja = 'ABIERTA' | 'DE_OTRO_DIA' | 'CERRADA';

export const TIPOS_MOVIMIENTO = ['INGRESO', 'GASTO', 'RETIRO'] as const;
export type TipoMovimientoCaja = (typeof TIPOS_MOVIMIENTO)[number];

export const TIPO_MOVIMIENTO_LABELS: Record<TipoMovimientoCaja, string> = {
  INGRESO: 'Ingreso',
  GASTO: 'Gasto',
  RETIRO: 'Retiro',
};

export interface CajaAbierta {
  id: number;
  estado: EstadoCaja;
  fechaApertura: string;
  abiertaPor: string;
  efectivoInicial: number;
}

export interface CajaVigente {
  estado: EstadoCajaVigente;
  caja: CajaAbierta | null;
  /** El contado del último cierre (o 0): lo que se propone para abrir. */
  efectivoPropuesto: number;
}

export interface MovimientoCajaRequest {
  tipo: TipoMovimientoCaja;
  importe: number;
  motivo: string;
}

export interface MovimientoCaja extends MovimientoCajaRequest {
  id: number;
  fecha: string;
  usuario: string;
}

/** Totales de una caja: lo ve el Dueño (en vivo o del historial) y quien cierra, recién en la respuesta del cierre. */
export interface DetalleCaja {
  id: number;
  estado: EstadoCaja;
  fechaApertura: string;
  abiertaPor: string;
  efectivoInicial: number;
  efectivoCierreAnterior: number | null;
  diferenciaApertura: number | null;
  fechaCierre: string | null;
  cerradaPor: string | null;
  ventas: Record<MedioPago, number>;
  totalVentas: number;
  /** Lo cobrado de cuenta corriente por medio (CC-053): el efectivo suma al esperado, la transferencia es informativa. */
  cobranzas: Record<MedioPago, number>;
  totalCobranzas: number;
  /**
   * Los recibos anulados contra esta caja por medio (CC-081): el efectivo RESTA del esperado, la transferencia es
   * informativa. Ausente si el backend es anterior a la anulación (el resumen omite el renglón).
   */
  anulaciones?: Record<MedioPago, number>;
  totalAnulaciones?: number;
  ingresos: number;
  gastos: number;
  retiros: number;
  efectivoEsperado: number;
  /** null mientras la caja está abierta. */
  efectivoContado: number | null;
  diferencia: number | null;
  movimientos: MovimientoCaja[];
}

/** Lo que manda el servidor: las cobranzas pueden faltar en una respuesta de antes de la cobranza (cajas viejas = 0). */
export type DetalleCajaRespuesta = Omit<DetalleCaja, 'cobranzas' | 'totalCobranzas'> &
  Partial<Pick<DetalleCaja, 'cobranzas' | 'totalCobranzas'>>;

function completarMedios(porMedio: Partial<Record<MedioPago, number>> | undefined): Record<MedioPago, number> {
  return Object.fromEntries(MEDIOS_PAGO.map((m) => [m, porMedio?.[m] ?? 0])) as Record<MedioPago, number>;
}

/**
 * El detalle con las cobranzas completas: un medio o un total que falta se lee como 0. Las anulaciones se completan
 * igual SOLO si el servidor las mandó; si la clave falta (backend viejo) quedan sin definir y el resumen omite el renglón.
 */
export function conCobranzas(respuesta: DetalleCajaRespuesta): DetalleCaja {
  const detalle: DetalleCaja = {
    ...respuesta,
    cobranzas: completarMedios(respuesta.cobranzas),
    totalCobranzas: respuesta.totalCobranzas ?? 0,
  };
  if (respuesta.anulaciones) {
    detalle.anulaciones = completarMedios(respuesta.anulaciones);
    detalle.totalAnulaciones = respuesta.totalAnulaciones ?? 0;
  } else {
    delete detalle.anulaciones;
    delete detalle.totalAnulaciones;
  }
  return detalle;
}

/** Una fila del historial de cajas cerradas. */
export interface CajaCerrada {
  id: number;
  fechaApertura: string;
  fechaCierre: string;
  abiertaPor: string;
  cerradaPor: string;
  efectivoInicial: number;
  diferenciaApertura: number | null;
  efectivoEsperado: number;
  efectivoContado: number;
  diferencia: number;
}

/** «dd/MM» de una fecha local del servidor, leída del texto: no pasa por la zona del navegador. */
export function diaDeCaja(fechaLocal: string): string {
  const [, mes, dia] = fechaLocal.slice(0, 10).split('-');
  return `${dia}/${mes}`;
}

/** «dd/MM HH:mm» de una fecha local del servidor; vacío si falta. */
export function fechaHoraLocal(fechaLocal: string | null | undefined): string {
  if (!fechaLocal) return '';
  return `${diaDeCaja(fechaLocal)} ${fechaLocal.slice(11, 16)}`;
}

/** Lo que frena la venta según la caja (mismos textos que el backend), o null si se puede vender. */
export function avisoParaVender(vigente: CajaVigente): string | null {
  if (vigente.estado === 'SIN_CAJA' || !vigente.caja) return 'Abrí la caja para vender.';
  if (vigente.estado === 'DE_OTRO_DIA') {
    return `La caja del ${diaDeCaja(vigente.caja.fechaApertura)} sigue abierta. Cerrala antes de vender.`;
  }
  return null;
}

/** La diferencia del cierre (contado − esperado) dicha en palabras: «Faltan $ 50,00», «Sobran…» o «Sin diferencia». */
export function textoDiferencia(diferencia: number): string {
  if (diferencia < 0) return `Faltan ${formatearMonto(-diferencia)}`;
  if (diferencia > 0) return `Sobran ${formatearMonto(diferencia)}`;
  return 'Sin diferencia';
}

/** Los tipos de movimiento que puede cargar el usuario: el retiro es solo del Dueño. */
export function tiposPara(esDueno: boolean): TipoMovimientoCaja[] {
  return TIPOS_MOVIMIENTO.filter((t) => esDueno || t !== 'RETIRO');
}
