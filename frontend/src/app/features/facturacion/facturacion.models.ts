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

/**
 * «Probar conexión» (C1 de la rama 6, CU-01 paso 4): los pasos van en orden y uno que falla corta los siguientes (el resto
 * no figura). En SIMULADOR trae el `aviso` «Simulador: no se probó contra ARCA».
 */
export type PasoPrueba = 'SERVIDORES' | 'AUTENTICACION' | 'PUNTO_DE_VENTA' | 'ULTIMOS_NUMEROS';

export const PASO_PRUEBA_LABELS: Record<PasoPrueba, string> = {
  SERVIDORES: 'Servidores de ARCA',
  AUTENTICACION: 'Autenticación (WSAA)',
  PUNTO_DE_VENTA: 'Punto de venta',
  ULTIMOS_NUMEROS: 'Últimos números',
};

export interface ResultadoPaso {
  paso: PasoPrueba;
  ok: boolean;
  /** Lo que dijo ARCA, legible: «AppServer OK, DbServer OK, AuthServer OK», el fault con su código, etc. */
  detalle: string;
}

/** El último número autorizado de un tipo de factura de la ferretería (según su condición frente al IVA). */
export interface UltimoNumero {
  cbteTipo: number;
  comprobante: string;
  numero: number;
}

export interface PruebaConexion {
  ambiente: AmbienteArca;
  /** Todos los pasos dieron OK: levanta el bloqueo de ARCA (C4). */
  ok: boolean;
  aviso: string | null;
  pasos: ResultadoPaso[];
  ultimos: UltimoNumero[];
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
