import { DocumentoFiscal, FiscalVenta, VentaFacturada, VentaParaFactura } from './factura.models';

/** Fixtures de la factura (contrato C1), para los specs de la hoja, del mostrador y de Ventas. */

export const LEYENDA_LEY_27618 =
  'El crédito fiscal discriminado en el presente comprobante, sólo podrá ser computado a efectos del Régimen de ' +
  'Sostenimiento e Inclusión Fiscal para Pequeños Contribuyentes de la Ley Nº 27.618';

/** Matriz chica de prueba: la hoja la dibuja tal cual, fila por fila. */
export const QR_MODULOS = ['1110111', '1000001', '1011101', '0000000', '1011101', '1000001', '1110111'];

const EMISOR_RI = {
  razonSocial: 'El Tornillo SRL',
  cuit: '30712345678',
  condicionIva: 'IVA RESPONSABLE INSCRIPTO',
  domicilioComercial: 'Av. Luro 1234, Mar del Plata',
  ingresosBrutos: '30712345678',
  inicioActividades: '2015-03-01',
};

/** B autorizada en producción a Consumidor Final, con el bloque de transparencia. */
export const documentoB = (cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal => ({
  validoComoFactura: true,
  letra: 'B',
  discriminaIva: false,
  codigo: 6,
  marca: null,
  puntoVenta: 1,
  numero: 12,
  fecha: '2026-10-28',
  cae: '70417054367476',
  caeVencimiento: '2026-11-07',
  emisor: { ...EMISOR_RI },
  receptor: {
    nombre: null,
    condicionIva: 'CONSUMIDOR FINAL',
    tipoDocumento: null,
    numeroDocumento: null,
    domicilio: null,
    aConsumidorFinal: true,
  },
  total: 1210,
  neto: 1000,
  iva: 210,
  noGravado: 0,
  exento: 0,
  alicuotas: [{ porcentaje: 21, base: 1000, importe: 210 }],
  transparencia: { ivaContenido: 210, otrosImpuestosNacionalesIndirectos: 0 },
  leyendaMonotributo: null,
  qr: 'https://www.arca.gob.ar/fe/qr/?p=eyJ2ZXIiOjF9',
  qrModulos: [...QR_MODULOS],
  ...cambios,
});

/** A autorizada a un Responsable Inscripto: discrimina el IVA. */
export const documentoARI = (cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal =>
  documentoB({
    letra: 'A',
    discriminaIva: true,
    codigo: 1,
    numero: 5,
    receptor: {
      nombre: 'Ferretería Norte SRL',
      condicionIva: 'IVA RESPONSABLE INSCRIPTO',
      tipoDocumento: 'CUIT',
      numeroDocumento: '30711111112',
      domicilio: 'Colón 2000, Mar del Plata',
      aConsumidorFinal: false,
    },
    transparencia: null,
    ...cambios,
  });

/** A autorizada a un Monotributista, con la leyenda de la Ley 27.618. */
export const documentoAMonotributo = (cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal =>
  documentoARI({
    receptor: {
      nombre: 'Juan Plomero',
      condicionIva: 'RESPONSABLE MONOTRIBUTO',
      tipoDocumento: 'CUIT',
      numeroDocumento: '20222222223',
      domicilio: 'Alberti 300, Mar del Plata',
      aConsumidorFinal: false,
    },
    leyendaMonotributo: LEYENDA_LEY_27618,
    ...cambios,
  });

/** C autorizada de un emisor monotributista: sin IVA. */
export const documentoC = (cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal =>
  documentoB({
    letra: 'C',
    codigo: 11,
    numero: 3,
    emisor: { ...EMISOR_RI, razonSocial: 'Pinturería Sol', cuit: '20333333334', condicionIva: 'RESPONSABLE MONOTRIBUTO' },
    neto: 1210,
    iva: 0,
    alicuotas: [],
    transparencia: null,
    ...cambios,
  });

/** Lo que el servidor pone en un documento que no es factura (S6): letra X y los siete campos fiscales en null. */
const NO_VALIDO = {
  validoComoFactura: false,
  letra: 'X',
  codigo: null,
  puntoVenta: null,
  numero: null,
  cae: null,
  caeVencimiento: null,
  qr: null,
  qrModulos: null,
} satisfies Partial<DocumentoFiscal>;

/** Un documento que no es factura (S6, S12), de una B: letra X y sin datos fiscales. */
export const documentoX = (marca: string, cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal =>
  documentoB({ ...NO_VALIDO, marca, ...cambios });

/** El X de una A a un Responsable Inscripto (S6 con el cuerpo de S13): discrimina el IVA, sin datos fiscales. */
export const documentoXDeA = (marca: string, cambios: Partial<DocumentoFiscal> = {}): DocumentoFiscal =>
  documentoARI({ ...NO_VALIDO, marca, ...cambios });

export const MARCA_SIMULADO = 'SIMULADO — sin validez fiscal';
export const MARCA_TICKET_PROVISORIO = 'TICKET PROVISORIO — factura en trámite con ARCA';

/** El `fiscal` de la respuesta: los 8 campos de la rama 3 más el documento. */
export const fiscalDe = (documento: DocumentoFiscal, cambios: Partial<FiscalVenta> = {}): FiscalVenta => ({
  estado: 'AUTORIZADO',
  clase: 'FACTURA',
  letra: documento.letra === 'X' ? 'B' : documento.letra,
  puntoVenta: documento.puntoVenta ?? 1,
  numero: documento.numero,
  cae: documento.cae,
  caeVencimiento: documento.caeVencimiento,
  ambiente: 'PRODUCCION',
  documento,
  ...cambios,
});

/** Lo de la venta que usa la hoja: 1 renglón de 2 martillos a $ 605 = $ 1.210. */
export const ventaParaFactura = (cambios: Partial<VentaParaFactura> = {}): VentaParaFactura => ({
  numero: 7,
  renglones: [
    {
      renglon: 1,
      descripcion: 'Martillo',
      cantidad: 2,
      unidad: 'UNIDAD',
      precioUnitario: 605,
      alicuotaIva: 'IVA_21',
      bruto: 1210,
      total: 1210,
      neto: 1000,
    },
  ],
  subtotal: 1210,
  descuento: 0,
  recargo: 0,
  remitoNumero: null,
  ...cambios,
});

export const ventaFacturada = (documento: DocumentoFiscal = documentoB(), cambios: Partial<VentaParaFactura> = {}): VentaFacturada => ({
  ...ventaParaFactura(cambios),
  fiscal: fiscalDe(documento),
});
