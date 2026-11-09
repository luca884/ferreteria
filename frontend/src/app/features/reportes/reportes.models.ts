import { UnidadDeMedida } from '../../core/formato/unidad';
import { HASTA_ANTERIOR_A_DESDE, rangoAlReves } from '../../shared/rango-de-fechas';
import { TipoMovimientoCaja } from '../caja/caja.models';

/**
 * Los reportes de ventas (como el menú Ventas de GDS) y el de ingresos, gastos y retiros de la caja (G73), con el nombre
 * que se ve en la pantalla.
 */
export const REPORTES = [
  { tipo: 'resumen', nombre: 'Resumen' },
  { tipo: 'por-usuario', nombre: 'Por usuario' },
  { tipo: 'por-medio-pago', nombre: 'Por medio de pago' },
  { tipo: 'por-periodo', nombre: 'Por día o mes' },
  { tipo: 'por-articulo', nombre: 'Por artículo' },
  { tipo: 'por-rubro', nombre: 'Por rubro' },
  { tipo: 'con-descuento', nombre: 'Con descuento' },
  { tipo: 'movimientos-caja', nombre: 'Ingresos, gastos y retiros' },
] as const;

export type TipoReporte = (typeof REPORTES)[number]['tipo'];
export type ReporteEnFilas = Exclude<TipoReporte, 'resumen' | 'movimientos-caja'>;
export type Agrupar = 'DIA' | 'MES';

/**
 * Desde CU-08 los importes y `ganancia` van netos de las notas de crédito (y las anulaciones internas), que restan en su
 * fecha; `cantidad` y `ventasSinCosto` cuentan solo ventas.
 */
export interface Resumen {
  desde: string;
  hasta: string;
  cantidad: number;
  /** El total con las notas de crédito restadas. */
  total: number;
  neto: number;
  iva: number;
  costo: number;
  /** La ganancia con NC: la de las ventas menos la de las notas de crédito. */
  ganancia: number;
  ventasSinCosto: number;
  /** El renglón «Notas de crédito»: cuántas y su total en positivo (lo que se restó). */
  notasDeCredito: { cantidad: number; total: number };
  /** La ganancia de las ventas solas (G66). */
  gananciaSinNotas: number;
  /** El total de las ventas solas: el renglón «Ventas», antes de restar las notas de crédito. */
  totalSinNotas: number;
}

export interface Filas<T> {
  desde: string;
  hasta: string;
  filas: T[];
}

export interface PorUsuario {
  usuarioId: number;
  nombre: string;
  cantidad: number;
  total: number;
  ganancia: number;
  ventasSinCosto: number;
}

export interface PorMedioPago {
  formaPago: string;
  medio: string;
  cantidad: number;
  monto: number;
}

export interface PorPeriodo {
  /** `2026-10-22` (día) o `2026-10` (mes). */
  periodo: string;
  cantidad: number;
  total: number;
  ganancia: number;
  ventasSinCosto: number;
}

export interface PorArticulo {
  articuloId: number;
  descripcion: string;
  cantidad: number;
  total: number;
  ganancia: number;
  renglonesSinCosto: number;
  renglones: number;
  /** La unidad del renglón vendido: la cantidad se escribe con ella («26,5 metros»). */
  unidad: UnidadDeMedida;
}

export interface PorRubro {
  rubro: string;
  renglones: number;
  total: number;
  ganancia: number;
  renglonesSinCosto: number;
}

export interface ConDescuento {
  ventaId: number;
  numero: number;
  /** Fecha y hora local de Buenos Aires, sin zona. */
  fecha: string;
  usuario: string;
  cliente: string | null;
  descuentoPorcentaje: number;
  descuento: number;
  total: number;
}

/** Un ingreso, gasto o retiro de la caja. `fecha` es local de Buenos Aires, sin zona. */
export interface MovimientoDeCaja {
  id: number;
  fecha: string;
  tipo: TipoMovimientoCaja;
  motivo: string;
  usuario: string;
  importe: number;
}

/** Los movimientos del rango y lo que suman por tipo (0 en los tipos sin movimientos). */
export interface ReporteMovimientos {
  desde: string;
  hasta: string;
  filas: MovimientoDeCaja[];
  totales: Record<TipoMovimientoCaja, number>;
}

/** Los tipos en plural, para el filtro y los totales: «Ingresos», «Gastos» y «Retiros». */
export const TIPO_MOVIMIENTO_PLURAL: Record<TipoMovimientoCaja, string> = {
  INGRESO: 'Ingresos',
  GASTO: 'Gastos',
  RETIRO: 'Retiros',
};

/** Lo que el servidor devuelve para cada reporte en filas. */
export interface FilasPorTipo {
  'por-usuario': PorUsuario;
  'por-medio-pago': PorMedioPago;
  'por-periodo': PorPeriodo;
  'por-articulo': PorArticulo;
  'por-rubro': PorRubro;
  'con-descuento': ConDescuento;
}

/** Las ventas (o renglones) sin costo, que la ganancia no incluye; null si no hay ninguna. */
export function avisoSinCosto(cantidad: number, que: 'ventas' | 'renglones'): string | null {
  if (cantidad === 0) return null;
  const una = cantidad === 1;
  const cosa = que === 'ventas' ? (una ? 'venta' : 'ventas') : una ? 'renglón' : 'renglones';
  const pronombre = que === 'ventas' ? (una ? 'la' : 'las') : una ? 'lo' : 'los';
  return `${cantidad} ${cosa} sin costo registrado: la ganancia no ${pronombre} incluye.`;
}

/** `2026-10-22` como `22/10/2026` y `2026-10` como `10/2026`. */
export function textoPeriodo(periodo: string): string {
  return periodo.split('-').reverse().join('/');
}

const DIA_BUENOS_AIRES = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires' });

/** La fecha de hoy en Buenos Aires, como `2026-10-22`. */
export function hoyEnBuenosAires(ahora = new Date()): string {
  return DIA_BUENOS_AIRES.format(ahora);
}

const DIAS_MAXIMOS = 366;
const UN_DIA = 24 * 60 * 60 * 1000;

/** El mismo control que el servidor, para avisar antes de pedir; null si el rango vale. */
export function errorDeRango(desde: string, hasta: string): string | null {
  if (!desde || !hasta) return 'Elegí las dos fechas.';
  if (rangoAlReves(desde, hasta)) return HASTA_ANTERIOR_A_DESDE;
  const dias = (Date.parse(`${hasta}T00:00:00Z`) - Date.parse(`${desde}T00:00:00Z`)) / UN_DIA + 1;
  return dias > DIAS_MAXIMOS ? 'El reporte admite hasta 366 días.' : null;
}
