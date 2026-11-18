import { Component, computed, input, output } from '@angular/core';
import { formatearMonto } from '../../core/formato/monto';
import { HojaInferior } from '../../shared/hoja-inferior';
import { RenglonTicket } from './ticket';

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });
const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/**
 * La hoja de un renglón del ticket en el celular (movil-sectores T8a, S7): un toque en el renglón la abre con las tres
 * acciones de F3, F7 y F10 —Cantidad, Descuento y Quitar renglón—, cada una con lo que hoy tiene el renglón. No hace
 * nada por sí sola: avisa cuál se eligió y el mostrador abre el mismo diálogo que abren esas teclas, con sus reglas y
 * sus mensajes (`DialogoCantidad`, `DialogoDescuento`, `DialogoConfirmacion`).
 */
@Component({
  selector: 'app-hoja-renglon',
  imports: [HojaInferior],
  template: `
    <app-hoja-inferior [titulo]="renglon().descripcion" [subtitulo]="subtitulo()" (cerrar)="cerrar.emit()">
      <button type="button" class="accion-hoja" (click)="cantidad.emit()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        <span>Cantidad<small>Ahora: {{ cantidadAhora() }}</small></span>
      </button>
      <button type="button" class="accion-hoja" (click)="descuento.emit()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M19 5 5 19" />
          <circle cx="7" cy="7" r="2.5" />
          <circle cx="17" cy="17" r="2.5" />
        </svg>
        <span>Descuento<small>Hoy: {{ descuentoHoy() }}</small></span>
      </button>
      <button type="button" class="accion-hoja peligro" (click)="quitar.emit()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
        </svg>
        <span>Quitar renglón</span>
      </button>
    </app-hoja-inferior>
  `,
})
export class HojaRenglon {
  readonly renglon = input.required<RenglonTicket>();

  readonly cantidad = output<void>();
  readonly descuento = output<void>();
  readonly quitar = output<void>();
  readonly cerrar = output<void>();

  protected readonly cantidadAhora = computed(() => NUMERO.format(this.renglon().cantidadMilesimas / 1000));
  protected readonly descuentoHoy = computed(() => {
    const bp = this.renglon().descuentoBp ?? 0;
    return bp > 0 ? `${PORCENTAJE.format(bp / 100)} %` : 'sin descuento';
  });
  /** «MEC-0088 · 1 × $ 4.800,00»: el código y lo que lleva el renglón. */
  protected readonly subtitulo = computed(() => {
    const r = this.renglon();
    return `${r.codigoInterno} · ${this.cantidadAhora()} × ${formatearMonto(r.precioCentavos / 100)}`;
  });
}
