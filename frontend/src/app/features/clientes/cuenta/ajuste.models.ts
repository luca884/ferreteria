import { formatearMonto } from '../../../core/formato/monto';
import { ImputacionCobro, MENSAJE_DECIMALES, PartidaRef, leerImporte } from './cobranza.models';

/**
 * Contrato de los ajustes manuales de la cuenta corriente (CU-12, GDS-20, G59), tipado contra el contrato de
 * `POST /api/clientes/{id}/cuenta-corriente/ajustes`. Un Recargo sube la deuda y un Descuento la baja; el Descuento se
 * imputa a los pendientes como un cobro y lo que no imputa queda «a cuenta» (saldo a favor). Los importes llegan en
 * pesos con 2 decimales y las cuentas se hacen en centavos enteros.
 */

export const TIPOS_AJUSTE = ['RECARGO', 'DESCUENTO'] as const;
export type TipoAjuste = (typeof TIPOS_AJUSTE)[number];

export const TIPO_AJUSTE_LABELS: Record<TipoAjuste, string> = {
  RECARGO: 'Recargo',
  DESCUENTO: 'Descuento',
};

/** El mismo tope que acepta el servidor: 13 enteros y 2 decimales. */
export const IMPORTE_MAXIMO_CENTAVOS = 999_999_999_999_999;

/**
 * POST /ajustes. `imputaciones` solo en un DESCUENTO (ausente o vacía es todo a cuenta); en un RECARGO con imputaciones el
 * servidor responde 400, así que no se manda.
 */
export interface AjusteRequest {
  /** UUID generado al abrir el diálogo: la misma clave en cada reintento de red. */
  clave: string;
  tipo: TipoAjuste;
  importe: number;
  motivo: string;
  imputaciones?: ImputacionCobro[];
}

/** A qué partida se imputó un Descuento: `comprobanteId` en una venta, `ajusteId` en un Recargo. */
export interface AjusteImputacion extends PartidaRef {
  comprobanteId: number | null;
  referencia: string;
  importe: number;
}

/** El ajuste registrado: lo mismo en el 201 y en el 200 de la clave repetida. `saldo` es el saldo DESPUÉS del ajuste. */
export interface AjusteResponse {
  ajusteId: number;
  numero: number;
  /** «Recargo N.º 3» o «Descuento N.º 3». */
  referencia: string;
  tipo: TipoAjuste;
  importe: number;
  motivo: string;
  fecha: string;
  saldo: number;
  imputaciones: AjusteImputacion[];
}

/** 201 si el ajuste es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface AjusteCobrado {
  ajuste: AjusteResponse;
  repetida: boolean;
}

/** El saldo (en centavos, negativo = a favor) después de un ajuste: el Recargo suma el importe y el Descuento lo resta. */
export function saldoTrasAjuste(saldoCentavos: number, tipo: TipoAjuste, importeCentavos: number): number {
  return tipo === 'RECARGO' ? saldoCentavos + importeCentavos : saldoCentavos - importeCentavos;
}

/** «$ 1.200,00» lo que debe; «$ 500,00 a favor» si el saldo es negativo. */
export function textoDeSaldo(saldo: number): string {
  return saldo < 0 ? `${formatearMonto(-saldo)} a favor` : formatearMonto(saldo);
}

/**
 * El error de un importe de ajuste escrito, o null si vale: hasta 2 decimales, mayor que cero y dentro del tope. Sin tope
 * de negocio (Q8); el servidor vuelve a validar.
 */
export function validarImporteAjuste(texto: string): string | null {
  if (texto.trim() === '') return 'Escribí el importe del ajuste.';
  const centavos = leerImporte(texto.trim());
  if (centavos === null) return MENSAJE_DECIMALES;
  if (centavos === 0) return 'El importe tiene que ser mayor que cero.';
  if (centavos > IMPORTE_MAXIMO_CENTAVOS) return 'El importe es demasiado grande.';
  return null;
}
