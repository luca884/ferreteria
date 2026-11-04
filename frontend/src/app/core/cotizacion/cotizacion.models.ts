/** Lado de una cotización: lo que paga el comprador (VENTA) o lo que cobra quien vende (COMPRA). */
export type LadoCotizacion = 'COMPRA' | 'VENTA';

export const LADO_LABELS: Record<LadoCotizacion, string> = {
  COMPRA: 'compra',
  VENTA: 'venta',
};

/** Un tipo que la ferretería eligió ver, por ejemplo `USD:oficial`. Sin valor guardado, importes y fechas vienen en null. */
export interface TipoCotizacion {
  /** `MONEDA:casa`, por ejemplo `USD:oficial` o `EUR:oficial`. */
  codigo: string;
  nombre: string;
  compra: number | null;
  venta: number | null;
  fuente: string | null;
  /** Fecha del mercado según la fuente (ISO). */
  actualizadaEn: string | null;
  /** Cuándo lo obtuvo el sistema (ISO). */
  obtenidaEn: string | null;
  /** Este valor lleva más de 1 hora sin renovarse (cada tipo tiene la suya). */
  desactualizada: boolean;
  /** Es el dólar en el que cotizan los precios de la ferretería. */
  esDePrecios: boolean;
}

/** El dólar de los precios: su tipo, el lado, el valor vigente (el del último recálculo) y desde cuándo rige. */
export interface PreciosCotizacion {
  codigo: string;
  lado: LadoCotizacion;
  valor: number | null;
  desde: string | null;
  /** Dólar nuevo que se aleja más del aviso de la ferretería y espera que el Dueño lo confirme; null si no hay. */
  aConfirmar: number | null;
}

/** Lo que devuelve `GET /cotizacion`, igual para todos los roles. */
export interface Cotizacion {
  activa: boolean;
  /** Más de 1 hora sin una respuesta exitosa de las APIs. */
  desactualizada: boolean;
  /** Última respuesta exitosa de cualquier API (ISO); null si todavía no hubo ninguna. */
  ultimaRespuesta: string | null;
  visibles: TipoCotizacion[];
  precios: PreciosCotizacion;
}

/** Un artículo en dólares en la vista previa del recálculo. */
export interface FilaVistaPrevia {
  id: number;
  codigoInterno: string;
  descripcion: string;
  activo: boolean;
  precioActual: number;
  precioNuevo: number;
  /** Precio nuevo menos actual; 0 si no cambia. */
  diferencia: number;
}

/** Lo que devuelve `GET /cotizacion/recalculo/vista-previa` (solo el Dueño): no cambia nada. */
export interface VistaPreviaRecalculo {
  /** El dólar con el que se recalcularía: hay que mandarlo al aplicar. */
  dolar: number;
  /** El dólar con el que están hoy los precios; null si nunca se fijó uno. */
  vigente: number | null;
  /** El dólar se aleja más del aviso de la ferretería: aplicar exige confirmarlo. */
  superaUmbral: boolean;
  /** El aviso armado por el backend cuando supera el umbral; null si no. */
  aviso: string | null;
  articulosQueCambian: number;
  /** Todos los artículos en dólares, por código. */
  articulos: FilaVistaPrevia[];
  /** Artículos que conservarían su precio porque sus ajustes lo dejarían en 0 o menos. */
  avisos: string[];
}

/** Lo que devuelve `POST /cotizacion/recalculo`. */
export interface RecalculoCotizacion {
  /** El dólar con el que se calcularon los precios (pesos por dólar). */
  cotizacion: number;
  /** Cuántos artículos cambiaron de precio; los que quedaron igual no cuentan. */
  articulosRecalculados: number;
  /**
   * CU-14: un aviso por artículo que conserva su precio porque sus ajustes lo dejarían en 0 o menos. Opcional por
   * compatibilidad con respuestas viejas.
   */
  avisos?: string[];
}
