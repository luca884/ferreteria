import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';
import { DocumentoNoFiscal } from './documento-no-fiscal.models';

export function emisorDePrueba(cambios: Partial<DatosFiscales> = {}): DatosFiscales {
  return {
    razonSocial: 'Tornillo SA',
    cuit: '30-71234567-8',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    domicilioComercial: 'Av. Luro 3200, Mar del Plata',
    inicioActividades: '2015-03-01',
    ingresosBrutos: '901-123456-7',
    ...cambios,
  };
}

/** Un remito con descuento global del 10 %, cliente y observaciones. */
export function documentoDePrueba(cambios: Partial<DocumentoNoFiscal> = {}): DocumentoNoFiscal {
  return {
    tipo: 'Remito',
    numero: 12,
    fecha: '2026-10-22T16:05:00',
    clienteNombre: 'Construcciones Pérez',
    observaciones: 'Entregar por la puerta de atrás',
    renglones: [
      {
        renglon: 1,
        descripcion: 'Martillo',
        cantidad: 2,
        precioUnitario: 1000,
        descuentoPorcentaje: 10,
        total: 1800,
      },
      {
        renglon: 2,
        descripcion: 'Clavos',
        cantidad: 1.5,
        precioUnitario: 400,
        descuentoPorcentaje: 0,
        total: 600,
      },
    ],
    subtotal: 2600,
    descuentoPorcentaje: 0,
    descuento: 200,
    total: 2400,
    ...cambios,
  };
}
