/**
 * La factura de una venta (CU-07 paso 5, CU-16): el `fiscal` de POST /api/ventas y GET /api/ventas/{id} (contrato C1) y
 * el resumen de cada fila de GET /api/ventas (C2). La apariencia la decide el servidor (`documento.validoComoFactura`):
 * la hoja pinta solo lo que trae `documento`, nunca `fiscal.letra` ni `fiscal.cae` ni la sesión.
 */

export type AmbienteFiscal = 'SIMULADOR' | 'HOMOLOGACION' | 'PRODUCCION';

export type EstadoFiscal =
  | 'PENDIENTE'
  | 'EN_CURSO'
  | 'EN_VERIFICACION'
  | 'AUTORIZADO'
  | 'RECHAZADO'
  | 'FUERA_DE_FECHA'
  | 'CAE_NO_COINCIDE'
  | 'SERIE_DESINCRONIZADA'
  /** El Dueño la dejó sin factura, con un motivo (rama 7, D10): definitivo. */
  | 'SIN_FACTURA';

/** Las alertas que traban la serie y resuelve soporte: la bandeja las muestra «En revisión», sin acciones (S13). */
export const ESTADOS_EN_REVISION: readonly EstadoFiscal[] = ['CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA'];

export type LetraFactura = 'A' | 'B' | 'C';
/** La letra impresa: X si el documento no es válido como factura. */
export type LetraDocumento = LetraFactura | 'X';

/** El emisor como quedó copiado en el registro fiscal. `condicionIva` ya viene como se imprime. */
export interface EmisorFiscal {
  razonSocial: string;
  cuit: string;
  condicionIva: string;
  domicilioComercial: string | null;
  ingresosBrutos: string | null;
  /** aaaa-mm-dd. */
  inicioActividades: string | null;
}

/** El receptor como se envió a ARCA. `aConsumidorFinal` si la condición es Consumidor Final (con o sin documento). */
export interface ReceptorFiscal {
  nombre: string | null;
  condicionIva: string;
  tipoDocumento: 'CUIT' | 'CUIL' | 'DNI' | null;
  numeroDocumento: string | null;
  domicilio: string | null;
  aConsumidorFinal: boolean;
}

export interface AlicuotaFiscal {
  porcentaje: number;
  base: number;
  importe: number;
}

/** Régimen de Transparencia Fiscal al Consumidor (Ley 27.743): solo en la B a Consumidor Final. */
export interface TransparenciaFiscal {
  ivaContenido: number;
  otrosImpuestosNacionalesIndirectos: number;
}

/**
 * Lo que se imprime. Con `validoComoFactura` false: `letra` "X" y `codigo`, `puntoVenta`, `numero`, `cae`,
 * `caeVencimiento`, `qr` y `qrModulos` en null, con la `marca` del caso.
 */
export interface DocumentoFiscal {
  validoComoFactura: boolean;
  letra: LetraDocumento;
  /**
   * True en la A, válida o X: renglones sin IVA con su alícuota y el pie con el neto gravado y el IVA por alícuota (S13).
   * Describe el cuerpo, no la apariencia fiscal: el X de una A se ve con el formato de la A.
   */
  discriminaIva: boolean;
  codigo: number | null;
  marca: string | null;
  puntoVenta: number | null;
  numero: number | null;
  /** CbteFch (aaaa-mm-dd); sin ella, el día de la venta en Buenos Aires. */
  fecha: string;
  cae: string | null;
  /** aaaa-mm-dd. */
  caeVencimiento: string | null;
  emisor: EmisorFiscal;
  receptor: ReceptorFiscal;
  total: number;
  neto: number;
  iva: number;
  noGravado: number;
  exento: number;
  /** Vacía en la C. */
  alicuotas: AlicuotaFiscal[];
  transparencia: TransparenciaFiscal | null;
  /** Solo en la A a un Monotributista (Ley 27.618). */
  leyendaMonotributo: string | null;
  qr: string | null;
  /** Filas de la matriz del QR sin zona de silencio: '1' oscuro, '0' claro, todas del mismo largo. */
  qrModulos: string[] | null;
}

/** El estado de la factura de la venta (rama 3) más el documento a imprimir. */
export interface FiscalVenta {
  estado: EstadoFiscal;
  clase: string;
  letra: LetraFactura;
  puntoVenta: number;
  numero: number | null;
  cae: string | null;
  caeVencimiento: string | null;
  ambiente: AmbienteFiscal;
  documento: DocumentoFiscal;
}

/** La factura de una fila del listado de ventas (C2). `letra` es la de la hoja: X si no es válida. */
export interface ResumenFiscal {
  letra: LetraDocumento;
  puntoVenta: number;
  numero: number | null;
  estado: EstadoFiscal;
  ambiente: AmbienteFiscal;
  validoComoFactura: boolean;
  /** Salió con ticket provisorio y ya está AUTORIZADO (rama 7, C1): la lista marca «Reimprimir factura». */
  reimprimir?: boolean;
}

/** Un renglón de la venta como lo necesita la hoja: `neto` es el subtotal sin IVA (la A lo muestra). */
export interface RenglonParaFactura {
  renglon: number;
  descripcion: string;
  cantidad: number;
  /** Con IVA. */
  precioUnitario: number;
  alicuotaIva: string;
  total: number;
  neto: number;
}

/** Lo que la hoja toma de la venta misma, aparte del documento fiscal. */
export interface VentaParaFactura {
  /** El número interno: solo lo muestra el documento X. */
  numero: number;
  renglones: readonly RenglonParaFactura[];
  subtotal?: number;
  descuento?: number;
  recargo?: number;
  /** El remito del que salió la venta (null o ausente si no salió de uno). */
  remitoNumero?: number | null;
}

/** Una venta con registro fiscal, lista para abrir su factura. */
export type VentaFacturada = VentaParaFactura & { fiscal: FiscalVenta };

/** «00001-00000012»: punto de venta de 5 dígitos y número de 8, como «Comprobantes en línea». */
export function numeroFiscal(puntoVenta: number, numero: number): string {
  return `${String(puntoVenta).padStart(5, '0')}-${String(numero).padStart(8, '0')}`;
}

export const ESTADO_FISCAL_LABELS: Record<EstadoFiscal, string> = {
  PENDIENTE: 'Pendiente',
  EN_CURSO: 'En curso',
  EN_VERIFICACION: 'En verificación',
  AUTORIZADO: 'Autorizada',
  RECHAZADO: 'Rechazada',
  FUERA_DE_FECHA: 'Fuera de fecha',
  CAE_NO_COINCIDE: 'En revisión',
  SERIE_DESINCRONIZADA: 'En revisión',
  SIN_FACTURA: 'Sin factura',
};
