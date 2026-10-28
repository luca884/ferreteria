import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { MontoPipe } from '../../core/formato/monto';
import { ALICUOTA_IVA_LABELS, AlicuotaIva } from '../../features/articulos/articulo.models';
import { documentoLegible } from '../../features/clientes/cliente.models';
import { barras } from '../../features/etiquetas/codigo-barras';
import { DocumentoFiscal, VentaParaFactura, numeroFiscal } from './factura.models';

const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/** Zona de silencio del QR, en módulos: `qrModulos` viene sin ella. */
const SILENCIO_QR = 4;

/**
 * La hoja de la factura (A, B, C o X), compartida por el mostrador y Ventas. Presentacional y fiscal: pinta solo lo que
 * trae `documento` (la copia del registro que armó el servidor) más los renglones y totales de la venta. Si el documento
 * no es válido como factura, es una X: nunca letra A/B/C, código, número fiscal, CAE ni QR, aunque vinieran.
 */
@Component({
  selector: 'app-factura-hoja',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './factura-hoja.html',
  styleUrl: './factura-hoja.css',
})
export class FacturaHoja {
  readonly documento = input.required<DocumentoFiscal>();
  readonly venta = input.required<VentaParaFactura>();

  protected readonly fecha = formatearFecha;

  protected readonly valido = computed(() => this.documento().validoComoFactura);
  /** La A discrimina el IVA: renglones sin IVA y el desglose por alícuota. */
  protected readonly discriminaIva = computed(() => this.valido() && this.documento().letra === 'A');
  protected readonly letra = computed(() => (this.valido() ? this.documento().letra : 'X'));
  protected readonly codigo = computed(() => {
    const codigo = this.documento().codigo;
    return this.valido() && codigo != null ? `COD. ${String(codigo).padStart(3, '0')}` : null;
  });
  protected readonly numero = computed(() => {
    const { puntoVenta, numero } = this.documento();
    return this.valido() && puntoVenta != null && numero != null ? numeroFiscal(puntoVenta, numero) : null;
  });
  /** «30-71234567-8». */
  protected readonly cuitEmisor = computed(() => {
    const cuit = this.documento().emisor.cuit;
    return `${cuit.slice(0, 2)}-${cuit.slice(2, 10)}-${cuit.slice(10)}`;
  });
  protected readonly documentoReceptor = computed(() => documentoLegible(this.documento().receptor));
  /** El CAE y su vencimiento, solo en la factura válida. */
  protected readonly cae = computed(() => {
    const { cae, caeVencimiento } = this.documento();
    return this.valido() && cae ? { numero: cae, vencimiento: formatearFecha(caeVencimiento) } : null;
  });

  /** Los renglones de la A: precio unitario y subtotal sin IVA (el `neto` del renglón) y la alícuota. */
  protected readonly renglonesSinIva = computed(() =>
    this.venta().renglones.map((r) => ({
      ...r,
      precioSinIva: Math.round((r.neto / r.cantidad) * 100) / 100,
      alicuota: ALICUOTA_IVA_LABELS[r.alicuotaIva as AlicuotaIva] ?? r.alicuotaIva,
    })),
  );
  protected readonly alicuotas = computed(() =>
    this.documento().alicuotas.map((a) => ({ ...a, texto: `${PORCENTAJE.format(a.porcentaje)} %` })),
  );

  /** Los módulos oscuros del QR como `<rect>`, fila por fila (como las barras de las etiquetas). Null sin QR. */
  protected readonly qr = computed(() => {
    const filas = this.documento().qrModulos;
    if (!this.valido() || !filas || filas.length === 0) return null;
    const lado = filas.length + 2 * SILENCIO_QR;
    return {
      viewBox: `${-SILENCIO_QR} ${-SILENCIO_QR} ${lado} ${lado}`,
      modulos: filas.flatMap((fila, y) => barras(fila).map((b) => ({ x: b.x, y, ancho: b.ancho }))),
    };
  });
}
