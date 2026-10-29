import { CONDICION_IVA_LABELS, CondicionIva } from '../clientes/cliente.models';

/** Las condiciones de IVA que puede tener una ferretería (el backend no admite «Consumidor final»). */
export type CondicionIvaFerreteria = Exclude<CondicionIva, 'CONSUMIDOR_FINAL'>;

export const CONDICIONES_IVA_FERRETERIA: readonly CondicionIvaFerreteria[] = [
  'RESPONSABLE_INSCRIPTO',
  'MONOTRIBUTISTA',
  'EXENTO',
];

export const CONDICION_IVA_FERRETERIA_LABELS: Record<CondicionIvaFerreteria, string> = {
  RESPONSABLE_INSCRIPTO: CONDICION_IVA_LABELS.RESPONSABLE_INSCRIPTO,
  MONOTRIBUTISTA: CONDICION_IVA_LABELS.MONOTRIBUTISTA,
  EXENTO: CONDICION_IVA_LABELS.EXENTO,
};

export type EstadoFerreteria = 'ACTIVA' | 'SUSPENDIDA';

export const ESTADO_FERRETERIA_LABELS: Record<EstadoFerreteria, string> = {
  ACTIVA: 'Activa',
  SUSPENDIDA: 'Suspendida',
};

/** El ambiente de ARCA de una ferretería. El super admin solo asigna o quita el simulador; los otros los fija el certificado. */
export type AmbienteArca = 'SIMULADOR' | 'HOMOLOGACION' | 'PRODUCCION';

export const AMBIENTE_ARCA_LABELS: Record<AmbienteArca, string> = {
  SIMULADOR: 'Simulador',
  HOMOLOGACION: 'Homologación',
  PRODUCCION: 'Producción',
};

/**
 * Contrato de /api/admin/ferreterias (FerreteriaAdminResponse): datos de alta, usuarios activos y configuración de ARCA.
 * Nada de ventas.
 */
export interface FerreteriaAdmin {
  id: number;
  razonSocial: string;
  /** Con guiones: 30-12345678-1. */
  cuit: string;
  condicionIva: CondicionIvaFerreteria;
  estado: EstadoFerreteria;
  /** Fecha y hora de alta, sin zona. */
  creadoEn: string;
  duenosActivos: number;
  vendedoresActivos: number;
  /** null si no tiene ningún Dueño activo. */
  duenoId: number | null;
  emailDueno: string | null;
  /** Marca «de prueba»: solo una ferretería de prueba puede usar el simulador. */
  dePrueba: boolean;
  /** null mientras no tenga ambiente. */
  ambiente: AmbienteArca | null;
  /** 1 con el simulador; null sin ambiente. */
  puntoVenta: number | null;
  facturacionHabilitada: boolean;
  /** Cuándo se prendió la facturación (ISO, UTC); null si no factura. */
  habilitadaEn: string | null;
}

export interface EstadoGeneral {
  activas: number;
  suspendidas: number;
  ferreterias: FerreteriaAdmin[];
}

/**
 * El chequeo del super admin (C2 de la rama 6, `POST /api/admin/arca/fedummy`): FEDummy de ARCA corrido desde el servidor
 * desplegado, para probar la red y el TLS. No usa certificados ni datos de ninguna ferretería.
 */
export interface FeDummyAmbiente {
  ok: boolean;
  /** Lo que contestó ARCA por cada servidor («OK» si anda); null si no hubo respuesta. */
  appServer: string | null;
  dbServer: string | null;
  authServer: string | null;
  /** Cuánto tardó la llamada; null si no hubo respuesta. */
  milisegundos: number | null;
  /** El motivo legible («No se pudo conectar con ARCA (timeout de conexión).», «Falló el handshake TLS.»); null si anduvo. */
  error: string | null;
}

export interface ChequeoFeDummy {
  homologacion: FeDummyAmbiente;
  produccion: FeDummyAmbiente;
}

export interface DuenoRequest {
  nombre: string;
  email: string;
  password: string;
}

/** Alta de una ferretería con su primer Dueño, en una sola operación. */
export interface AltaFerreteriaRequest {
  razonSocial: string;
  cuit: string;
  condicionIva: CondicionIvaFerreteria;
  dueno: DuenoRequest;
}
