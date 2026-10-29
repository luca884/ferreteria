import { formatearFecha } from '../../core/formato/fecha-hora';
import { AmbienteArca } from '../admin-ferreterias/ferreteria-admin.models';

/**
 * Contrato C1 de la rama 5 (`/api/facturacion/**`, solo el Dueño): el ambiente, el punto de venta y la credencial de ARCA
 * de la ferretería. Ninguna respuesta trae la clave privada: nace cifrada en el servidor y nunca sale de ahí (D8).
 */

/** Cómo está la credencial según su vencimiento (aviso 30 días antes) y si la clave guardada se puede leer. */
export type EstadoCredencial = 'VIGENTE' | 'POR_VENCER' | 'VENCIDA' | 'ILEGIBLE';

export const ESTADO_CREDENCIAL_LABELS: Record<EstadoCredencial, string> = {
  VIGENTE: 'Vigente',
  POR_VENCER: 'Por vencer',
  VENCIDA: 'Vencida',
  ILEGIBLE: 'Ilegible',
};

/** El certificado activo, sin secretos. Las fechas van sin hora (`2028-10-27`). */
export interface CredencialArca {
  cuit: string;
  emisor: string;
  ambiente: AmbienteArca;
  vigenteDesde: string;
  vigenteHasta: string;
  /** Huella de la clave pública, en hexadecimal con dos puntos. */
  huella: string;
  estado: EstadoCredencial;
  diasParaVencer: number;
}

/** El pedido de certificado (CSR) que espera el .crt de ARCA. `creadoEn` es un instante UTC. */
export interface PedidoPendiente {
  creadoEn: string;
  cuit: string;
  huella: string;
}

export interface ConfiguracionFacturacion {
  /** null hasta que el primer certificado lo fija (o el super admin asigna el simulador). */
  ambiente: AmbienteArca | null;
  puntoVenta: number | null;
  puntoVentaEditable: boolean;
  /** Por qué no se puede cambiar el punto de venta; null si se puede. */
  motivoPuntoVentaTrabado: string | null;
  facturacionHabilitada: boolean;
  /** La ferretería está en SIMULADOR: se puede firmar el pedido con la CA de simulación. */
  simulador: boolean;
  credencial: CredencialArca | null;
  pedido: PedidoPendiente | null;
  avisos: string[];
}

/** El CSR en PEM y el nombre con el que se baja. */
export interface PedidoCertificado {
  csr: string;
  archivo: string;
}

/** El vencimiento que el mostrador le avisa al Dueño (S12): solo con la credencial por vencer o vencida. */
export interface VencimientoCertificado {
  vencido: boolean;
  /** dd/mm/aaaa. */
  fecha: string;
}

export function vencimientoParaElMostrador(config: ConfiguracionFacturacion): VencimientoCertificado | null {
  const credencial = config.credencial;
  if (credencial?.estado !== 'POR_VENCER' && credencial?.estado !== 'VENCIDA') return null;
  return { vencido: credencial.estado === 'VENCIDA', fecha: formatearFecha(credencial.vigenteHasta) };
}
