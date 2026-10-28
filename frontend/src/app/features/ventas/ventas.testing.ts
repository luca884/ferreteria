import { VentaConsultada, VentaResumen } from './ventas.models';

/** Una venta como la devuelve GET /api/ventas/{id}, para los specs. */
export const ventaDePrueba = (cambios: Partial<VentaConsultada> = {}): VentaConsultada => ({
  ventaId: 31,
  numero: 7,
  total: 2970,
  fecha: '2026-10-22T16:05:00',
  subtotal: 3000,
  descuentoPorcentaje: 10,
  descuento: 300,
  recargo: 270,
  pagos: [
    { formaPagoId: 2, formaPago: 'Crédito 1 pago', medio: 'CREDITO', recargoPorcentaje: 10, recargo: 270, monto: 2970 },
  ],
  renglones: [
    {
      renglon: 1,
      articuloId: 1,
      descripcion: 'Martillo',
      unidad: 'UNIDAD',
      cantidad: 2,
      precioUnitario: 1500,
      alicuotaIva: 'IVA_21',
      bruto: 3000,
      descuentoPorcentaje: 0,
      total: 2970,
      neto: 2454.55,
      iva: 515.45,
      recargo: 270,
    },
  ],
  sinStock: [],
  facturable: true,
  clienteNombre: 'Construcciones Pérez',
  usuario: 'Ana Vendedora',
  ...cambios,
});

/** Una fila del listado, para los specs. */
export const resumenDePrueba = (numero: number, cambios: Partial<VentaResumen> = {}): VentaResumen => ({
  ventaId: numero * 10,
  numero,
  fecha: '2026-10-22T16:05:00',
  clienteNombre: `Cliente ${numero}`,
  total: 3000,
  usuario: 'Ana Vendedora',
  facturable: true,
  // C2: sin registro fiscal viene null.
  fiscal: null,
  ...cambios,
});
