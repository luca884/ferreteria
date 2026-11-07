import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { cuitConGuiones } from '../../core/formato/cuit';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { MontoPipe } from '../../core/formato/monto';
import { CONDICION_IVA_FERRETERIA_LABELS } from '../../features/admin-ferreterias/ferreteria-admin.models';
import { fechaCompletaLocal } from '../../features/clientes/cuenta/cobranza.models';
import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';
import { fechaDia } from '../../features/presupuestos/presupuesto.models';
import { porcentajeDescuentoGeneral } from '../descuento-general';
import {
  DocumentoNoFiscal,
  LETRA_DOCUMENTO_NO_FISCAL,
  LEYENDA_DOCUMENTO_NO_FISCAL,
} from './documento-no-fiscal.models';

/**
 * La hoja del remito y del presupuesto (cu-07-remito-presupuesto-imprimible), con el mismo armado que la factura: el emisor,
 * la letra en su recuadro y el comprobante. Presentacional y NO fiscal: la letra es siempre X y dice «DOCUMENTO NO VALIDO COMO
 * FACTURA»; no tiene código, número fiscal, CAE ni QR. El emisor son los datos actuales de la ferretería.
 */
@Component({
  selector: 'app-documento-no-fiscal-hoja',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './documento-no-fiscal-hoja.html',
  styleUrl: './documento-no-fiscal-hoja.css',
})
export class DocumentoNoFiscalHoja {
  readonly documento = input.required<DocumentoNoFiscal>();
  readonly emisor = input.required<DatosFiscales>();

  protected readonly letra = LETRA_DOCUMENTO_NO_FISCAL;
  protected readonly leyenda = LEYENDA_DOCUMENTO_NO_FISCAL;
  protected readonly fecha = computed(() => fechaCompletaLocal(this.documento().fecha));
  protected readonly validoHasta = computed(() => fechaDia(this.documento().validoHasta));
  protected readonly inicioActividades = computed(() =>
    formatearFecha(this.emisor().inicioActividades),
  );
  protected readonly condicionIva = computed(
    () => CONDICION_IVA_FERRETERIA_LABELS[this.emisor().condicionIva],
  );
  protected readonly porcentajeGeneral = computed(() =>
    porcentajeDescuentoGeneral(this.documento()),
  );
  /** «30-71234567-8», venga con guiones o solo con dígitos. */
  protected readonly cuit = computed(() => cuitConGuiones(this.emisor().cuit));
}
