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

/** Contrato de /api/admin/ferreterias (FerreteriaAdminResponse): datos de alta y usuarios activos. Nada de ventas. */
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
}

export interface EstadoGeneral {
  activas: number;
  suspendidas: number;
  ferreterias: FerreteriaAdmin[];
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

/** Edición: razón social y condición de IVA. El CUIT no se edita. */
export interface EdicionFerreteriaRequest {
  razonSocial: string;
  condicionIva: CondicionIvaFerreteria;
}
