import { MedioPago } from '../mostrador/venta.models';

/** Contrato de /api/formas-pago, tipado contra FormaPagoRequest / FormaPagoResponse (GDS-11). */
export interface FormaPago {
  id: number;
  nombre: string;
  /** El medio al que pertenece: es el que suma la caja. */
  medio: MedioPago;
  /** Recargo en %, de 0 a 200. Se suma al total de la parte de la venta que se paga con esta forma. */
  recargoPorcentaje: number;
  activa: boolean;
  orden: number;
}

export interface FormaPagoRequest {
  nombre: string;
  medio: MedioPago;
  recargoPorcentaje: number;
  activa: boolean;
}
