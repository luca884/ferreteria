export type TipoDocumento = 'DNI' | 'CUIT' | 'CUIL';
export type CondicionIva = 'CONSUMIDOR_FINAL' | 'RESPONSABLE_INSCRIPTO' | 'MONOTRIBUTISTA' | 'EXENTO';

export const TIPOS_DOCUMENTO: readonly TipoDocumento[] = ['DNI', 'CUIT', 'CUIL'];
export const CONDICIONES_IVA: readonly CondicionIva[] = [
  'CONSUMIDOR_FINAL',
  'RESPONSABLE_INSCRIPTO',
  'MONOTRIBUTISTA',
  'EXENTO',
];

export const CONDICION_IVA_LABELS: Record<CondicionIva, string> = {
  CONSUMIDOR_FINAL: 'Consumidor final',
  RESPONSABLE_INSCRIPTO: 'Responsable inscripto',
  MONOTRIBUTISTA: 'Monotributista',
  EXENTO: 'Exento',
};

/** CU-11 (D4): Responsable inscripto, Monotributista y Exento llevan CUIT y domicilio; Consumidor final, no. */
export function requiereDatosFiscales(condicion: CondicionIva): boolean {
  return condicion !== 'CONSUMIDOR_FINAL';
}

/** CU-11 (D4): los mismos mensajes que da el servidor cuando a un RI, Monotributista o Exento le falta el CUIT o el domicilio. */
export const FALTA_CUIT = 'Falta el CUIT: es obligatorio para Responsable Inscripto, Monotributista o Exento.';
export const NO_ES_CUIT = 'Para Responsable Inscripto, Monotributista o Exento el documento tiene que ser el CUIT.';
export const FALTA_DOMICILIO = 'Falta el domicilio: es obligatorio para Responsable Inscripto, Monotributista o Exento.';

/** Contrato de /api/clientes, tipado contra ClienteResponse (CL-003). Lo que no se cargó no viaja. */
export interface Cliente {
  id: number;
  razonSocial: string;
  tipoDocumento?: TipoDocumento;
  /** Solo dígitos: el servidor guarda el documento sin puntos ni guiones. */
  numeroDocumento?: string;
  condicionIva: CondicionIva;
  domicilio?: string;
  telefono?: string;
  whatsapp?: string;
  email?: string;
  activo: boolean;
  /** Si el cliente tiene cuenta corriente habilitada (límite cargado, 0 incluido). Lo ven todos los roles (CC-019). */
  cuentaCorrienteHabilitada: boolean;
  /** Solo el Dueño y solo con la cuenta habilitada: lo que no se manda no se enmascara (CC-007). */
  limiteCuentaCorriente?: number;
  /** Solo el Dueño: lo que debe (positivo) o tiene a favor (negativo). */
  saldo?: number;
}

/**
 * La cuenta corriente de un cliente: GET y PUT de /clientes/{id}/cuenta-corriente (CC-018, CC-019). `alcanza` viene solo si
 * se pidió con importe; `limite`, `saldo` y `disponible`, solo para el Dueño.
 */
export interface CuentaCliente {
  habilitada: boolean;
  alcanza?: boolean;
  limite?: number;
  saldo?: number;
  disponible?: number;
}

export interface ClienteRequest {
  razonSocial: string;
  tipoDocumento?: TipoDocumento;
  numeroDocumento?: string;
  condicionIva: CondicionIva;
  domicilio?: string;
  telefono?: string;
  whatsapp?: string;
  email?: string;
}

/** Los filtros de la lista: `q` busca por razón social (contiene) o documento (empieza con). */
export interface FiltroClientes {
  q?: string;
  soloActivos?: boolean;
  page?: number;
  size?: number;
}

/** El documento como se muestra: «CUIT 30-71234567-1», «DNI 12.345.678»; vacío si el cliente no lo tiene. */
export function documentoLegible(c: { tipoDocumento?: TipoDocumento | null; numeroDocumento?: string | null }): string {
  const n = c.numeroDocumento;
  if (!c.tipoDocumento || !n) return '';
  if (c.tipoDocumento === 'DNI') return `DNI ${n.replace(/\B(?=(\d{3})+(?!\d))/g, '.')}`;
  return `${c.tipoDocumento} ${n.slice(0, 2)}-${n.slice(2, 10)}-${n.slice(10)}`;
}
