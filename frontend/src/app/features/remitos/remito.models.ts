import { cantidadConUnidad } from '../../core/formato/unidad';
import {
  DiferenciaFacturada,
  DocumentoNoFiscal,
  FacturadoPor,
} from '../../shared/documento-no-fiscal/documento-no-fiscal.models';
import { Unidad } from '../articulos/articulo.models';
import { RenglonVentaRequest } from '../mostrador/venta.models';

/** Contrato de /api/remitos (G55), tipado contra EmitirRemitoRequest / RemitoResponse. */

export const ESTADOS_REMITO = ['EMITIDO', 'FACTURADO', 'ANULADO'] as const;
export type EstadoRemito = (typeof ESTADOS_REMITO)[number];

export const ETIQUETA_ESTADO_REMITO: Record<EstadoRemito, string> = {
  EMITIDO: 'Emitido',
  FACTURADO: 'Facturado',
  ANULADO: 'Anulado',
};

/** El color de la pastilla del estado en el detalle (informacion-y-links, S4): `styles.css` → `.pastilla-*`. */
export const COLOR_ESTADO_REMITO: Record<EstadoRemito, 'neutro' | 'exito' | 'peligro'> = {
  EMITIDO: 'neutro',
  FACTURADO: 'exito',
  ANULADO: 'peligro',
};

export const CLIENTE_REMITO_MAXIMO = 80;
export const OBSERVACIONES_REMITO_MAXIMO = 200;

export interface EmitirRemitoRequest {
  /** UUID del ticket: la misma clave en cada reintento. */
  clave: string;
  renglones: RenglonVentaRequest[];
  /** El total del ticket, ya con los descuentos. El remito no lleva recargo. */
  totalMostrado: number;
  /** Descuento global en %, aplicado a cada renglón. Se omite si no hay. */
  descuentoPorcentaje?: number;
  /** El cliente elegido con F9 (CL-005): si va, el servidor usa su razón social y se ignora `clienteNombre`. */
  clienteId?: number;
  clienteNombre?: string;
  observaciones?: string;
}

export interface RenglonRemitoResponse {
  renglon: number;
  articuloId: number;
  descripcion: string;
  unidad: Unidad;
  cantidad: number;
  precioUnitario: number;
  alicuotaIva: string;
  bruto: number;
  descuentoPorcentaje: number;
  total: number;
  neto: number;
  iva: number;
}

/** Un artículo que quedó con stock negativo al emitir el remito. */
export interface RemitoSinStock {
  articuloId: number;
  descripcion: string;
  stockActual: number;
}

export interface RemitoResponse {
  remitoId: number;
  numero: number;
  estado: EstadoRemito;
  /** LocalDateTime ISO sin zona. */
  fecha: string;
  /** Solo si se eligió un cliente de la tabla (CL-004); `clienteNombre` es la copia de su razón social o el texto libre. */
  clienteId?: number;
  clienteNombre?: string;
  observaciones?: string;
  subtotal: number;
  descuentoPorcentaje: number;
  descuento: number;
  neto: number;
  iva: number;
  total: number;
  renglones: RenglonRemitoResponse[];
  /** Solo viene al emitir; en un remito repetido viene vacío. */
  sinStock?: RemitoSinStock[];
  /** Solo en el detalle de un remito FACTURADO: la venta que lo facturó y, si tiene factura válida, cuál. */
  facturadoPor?: FacturadoPor;
  /** Quién lo emitió (su nombre): solo en el detalle (informacion-y-links, S4). */
  usuario?: string;
}

/**
 * «Bulon 3/8: se facturaron 250 de 300 unidades; 50 unidades volvieron al stock.» (informacion-y-links, S5). Si se facturó
 * de más, no volvió nada: «se facturaron 12 de 10 unidades.».
 */
export function textoDeDiferencia(d: DiferenciaFacturada): string {
  const facturado = cantidadConUnidad(d.facturado, d.unidad).split(' ')[0];
  const base = `${d.descripcion}: se facturaron ${facturado} de ${cantidadConUnidad(d.remitido, d.unidad)}`;
  const vuelto = Math.round((d.remitido - d.facturado) * 1000) / 1000;
  return vuelto > 0 ? `${base}; ${cantidadConUnidad(vuelto, d.unidad)} volvieron al stock.` : `${base}.`;
}

/** Lo que la hoja imprimible necesita de un remito. */
export function documentoDeRemito(r: RemitoResponse): DocumentoNoFiscal {
  return {
    tipo: 'Remito',
    numero: r.numero,
    fecha: r.fecha,
    clienteNombre: r.clienteNombre,
    observaciones: r.observaciones,
    renglones: r.renglones,
    subtotal: r.subtotal,
    descuentoPorcentaje: r.descuentoPorcentaje,
    descuento: r.descuento,
    total: r.total,
    facturadoPor: r.facturadoPor,
  };
}

/** Una fila del listado: lo justo para la grilla, sin renglones. */
export interface RemitoResumen {
  remitoId: number;
  numero: number;
  estado: EstadoRemito;
  fecha: string;
  clienteNombre?: string;
  total: number;
}

/** Filtros del listado; `desde` y `hasta` son días (aaaa-mm-dd), ambos inclusive. `page` empieza en 0. */
export interface FiltroRemitos {
  page: number;
  size?: number;
  estado?: EstadoRemito;
  numero?: number;
  desde?: string;
  hasta?: string;
}

/** Lo que se completa en el diálogo del remito: los dos datos son opcionales. */
export interface DatosRemito {
  clienteNombre?: string;
  observaciones?: string;
}

/** 201 si el remito es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface RemitoEmitido {
  remito: RemitoResponse;
  repetido: boolean;
}
