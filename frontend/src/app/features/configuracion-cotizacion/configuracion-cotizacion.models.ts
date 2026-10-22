import { LadoCotizacion, RecalculoCotizacion } from '../../core/cotizacion/cotizacion.models';

/** Lo que devuelve `GET /configuracion-cotizacion` (solo el Dueño). */
export interface ConfiguracionCotizacion {
  activa: boolean;
  /** UN solo dólar, `MONEDA:casa`, por ejemplo `USD:oficial`. El euro y el real no sirven. */
  dolarPrecios: string;
  ladoPrecios: LadoCotizacion;
  /** Los tipos que se muestran en el menú (varios a la vez). */
  visibles: string[];
  /** `HH:mm`, hora de Buenos Aires. */
  horaRecalculo: string;
  /** El dólar con el que se calcularon los precios la última vez; null si todavía no se fijó ninguno. */
  vigente: number | null;
  vigenteDesde: string | null;
  /** Un dólar nuevo que se aleja más del 20 % del vigente: los precios no cambian hasta que el Dueño lo confirme. */
  aConfirmar: DolarAConfirmar | null;
}

export interface DolarAConfirmar {
  nuevo: number;
  vigente: number;
  desvioPorcentaje: number;
  /** El aviso tal cual se muestra, armado por el backend. */
  mensaje: string;
}

/** Lo que manda `PUT /configuracion-cotizacion`: el vigente no se edita, lo fija el recálculo; el aviso es solo lectura. */
export type ConfiguracionCotizacionRequest = Omit<ConfiguracionCotizacion, 'vigente' | 'vigenteDesde' | 'aConfirmar'>;

/** Los tipos que el sistema conoce, en el orden en que se ofrecen (los mismos que valida el backend). */
export interface OpcionCotizacion {
  codigo: string;
  nombre: string;
  /** El euro y el real son solo para mostrar: no sirven como dólar de los precios. */
  esDolar: boolean;
}

export const OPCIONES_COTIZACION: readonly OpcionCotizacion[] = [
  { codigo: 'USD:oficial', nombre: 'Dólar oficial', esDolar: true },
  { codigo: 'USD:blue', nombre: 'Dólar blue', esDolar: true },
  { codigo: 'USD:bolsa', nombre: 'Dólar MEP', esDolar: true },
  { codigo: 'USD:contadoconliqui', nombre: 'Dólar contado con liqui', esDolar: true },
  { codigo: 'USD:mayorista', nombre: 'Dólar mayorista', esDolar: true },
  { codigo: 'USD:tarjeta', nombre: 'Dólar tarjeta', esDolar: true },
  { codigo: 'USD:cripto', nombre: 'Dólar cripto', esDolar: true },
  { codigo: 'EUR:oficial', nombre: 'Euro', esDolar: false },
  { codigo: 'BRL:oficial', nombre: 'Real', esDolar: false },
];

/**
 * Lo que devuelve «Recalcular ahora»: además del resultado, un aviso por artículo que conserva su precio porque sus
 * ajustes lo dejarían en 0 o menos. Opcional por compatibilidad con respuestas viejas.
 */
export type RecalculoConAvisos = RecalculoCotizacion & { avisos?: string[] };
