import { PageResponse } from '../../../core/api/page.models';

/**
 * Contrato del extracto de la cuenta corriente (CC-072..CC-080), tipado contra ExtractoResponse y LineaExtractoResponse
 * del backend. Los importes llegan como número JSON (1600.00 → 1600): se formatean siempre con 2 decimales. Las fechas son
 * LocalDateTime ISO SIN zona (hora de Buenos Aires): se leen del texto, sin convertir.
 */

/** Los tipos del libro, tal como los acepta el backend (en mayúsculas, un solo tipo por filtro). */
export const TIPOS_MOVIMIENTO_CUENTA = ['VENTA', 'COBRO', 'ANULACION', 'AJUSTE', 'NC'] as const;
export type TipoMovimiento = (typeof TIPOS_MOVIMIENTO_CUENTA)[number];

export const TIPO_MOVIMIENTO_LABELS: Record<TipoMovimiento, string> = {
  VENTA: 'Venta',
  COBRO: 'Cobro',
  ANULACION: 'Anulación',
  AJUSTE: 'Ajuste',
  NC: 'NC',
};

/** Una línea del libro con el saldo del cliente DESPUÉS de ella (el campo se llama `saldo`, no `saldoDespues`). */
export interface LineaExtracto {
  id: number;
  fecha: string;
  tipo: TipoMovimiento;
  /** «Venta N.º 7», «Recibo N.º 14», «Anulación Recibo N.º 14»: copiada al asentar el movimiento. */
  referencia: string;
  debe: number;
  haber: number;
  saldo: number;
  usuario: string | null;
  /** Solo en COBRO y ANULACION. */
  reciboId: number | null;
  /** Solo en VENTA. */
  comprobanteId: number | null;
  /** El motivo de la anulación (solo en ANULACION). */
  motivo: string | null;
  excedioLimite: boolean;
  autorizadoPor: string | null;
  /** Verdadero en un COBRO cuyo recibo ya tiene su ANULACION. */
  anulado: boolean;
}

/** GET /api/clientes/{id}/cuenta-corriente/extracto: el encabezado del período y las líneas paginadas. */
export interface Extracto {
  clienteId: number;
  /** La razón social del cliente (no el nombre copiado en un recibo). */
  clienteNombre: string;
  saldoActual: number;
  desde: string | null;
  hasta: string | null;
  tipo: TipoMovimiento | null;
  saldoInicial: number;
  totalDebe: number;
  totalHaber: number;
  saldoFinal: number;
  movimientos: PageResponse<LineaExtracto>;
  /** Solo en una impresión que superó el tope: se devolvieron los primeros TOPE_IMPRESION. */
  truncado: boolean;
}

/** Lo que se elige en pantalla. Un campo vacío ('') es «sin filtro» y no se manda al backend. */
export interface FiltroExtracto {
  /** yyyy-MM-dd, o ''. */
  desde: string;
  /** yyyy-MM-dd, o ''. */
  hasta: string;
  tipo: TipoMovimiento | '';
}

export const FILTRO_VACIO: FiltroExtracto = { desde: '', hasta: '', tipo: '' };

/** Movimientos por página en la pantalla (el default del backend, que acota a [1, 100]). */
export const TAMANIO_PAGINA_EXTRACTO = 20;

/** Cuántos movimientos devuelve como máximo una impresión (el tope del backend); si hay más, `truncado`. */
export const TOPE_IMPRESION = 2000;

export function hayFiltro(filtro: FiltroExtracto): boolean {
  return filtro.desde.trim() !== '' || filtro.hasta.trim() !== '' || filtro.tipo !== '';
}

/** Solo viajan los filtros con valor: los vacíos son «sin filtro» y el backend no recibe centinelas. */
function paramsDeFiltro(filtro: FiltroExtracto): Record<string, string> {
  const params: Record<string, string> = {};
  if (filtro.desde.trim() !== '') params['desde'] = filtro.desde.trim();
  if (filtro.hasta.trim() !== '') params['hasta'] = filtro.hasta.trim();
  if (filtro.tipo !== '') params['tipo'] = filtro.tipo;
  return params;
}

/** Los parámetros de una página de la pantalla: los filtros con valor, `page` (desde 0) y `size`. */
export function filtroAParams(
  filtro: FiltroExtracto,
  page = 0,
  size = TAMANIO_PAGINA_EXTRACTO,
): Record<string, string> {
  return { ...paramsDeFiltro(filtro), page: String(page), size: String(size) };
}

/** Los parámetros de la impresión: los filtros con valor e `impresion=true`, sin paginar (un solo pedido). */
export function paramsDeImpresion(filtro: FiltroExtracto): Record<string, string> {
  return { ...paramsDeFiltro(filtro), impresion: 'true' };
}

/** «Anular» se ofrece solo en un COBRO vigente (ni anulado, ni venta, ni ajuste, ni NC, ni la ANULACION misma). */
export function puedeAnular(linea: LineaExtracto): boolean {
  return linea.tipo === 'COBRO' && !linea.anulado && linea.reciboId !== null;
}

/** «Ver recibo» / «Reimprimir» en cualquier COBRO (también anulado) y en la ANULACION, que abre el mismo recibo. */
export function puedeVerRecibo(linea: LineaExtracto): boolean {
  return (linea.tipo === 'COBRO' || linea.tipo === 'ANULACION') && linea.reciboId !== null;
}
