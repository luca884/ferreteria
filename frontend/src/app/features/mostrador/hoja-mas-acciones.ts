import { Component, computed, input, output } from '@angular/core';
import { formatearMonto } from '../../core/formato/monto';
import { HojaInferior } from '../../shared/hoja-inferior';
import { ariaDe } from '../../shared/motivo-de-accion';
import { AccionBotonera } from './botonera';

/**
 * «Más acciones» del ticket en el celular (movil-sectores T8b, S7): las acciones de todo el ticket que en el escritorio
 * son botones de la botonera —Cliente, Descuento total, Presupuesto, Remito y Vaciar— en una hoja inferior. «Cobrar» ya
 * está en la barra fija; Cantidad, Descuento y Quitar son de cada renglón y están en su hoja (`HojaRenglon`).
 *
 * No hace nada por sí sola: avisa cuál se eligió y el mostrador hace lo mismo que si se hubiera apretado el botón de la
 * botonera (`ejecutar`). Los motivos son la tabla del mostrador (`motivos`, S4): una acción apagada se ve apagada, dice
 * por qué en vez de su ayuda y al tocarla no avisa nada. No distingue roles: la botonera tampoco lo hace.
 */
@Component({
  selector: 'app-hoja-mas-acciones',
  imports: [HojaInferior],
  template: `
    <app-hoja-inferior titulo="Acciones del ticket" [subtitulo]="subtitulo()" (cerrar)="cerrar.emit()">
      <button type="button" class="accion-hoja" [attr.aria-disabled]="ariaDe(motivos().cliente)" (click)="elegir('cliente')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="8" r="3.5" />
          <path d="M5 20c1-4 4-6 7-6s6 2 7 6" />
        </svg>
        <span>Cliente<small>{{ motivos().cliente ?? cliente() ?? 'Consumidor Final' }}</small></span>
      </button>
      <button type="button" class="accion-hoja" [attr.aria-disabled]="ariaDe(motivos().descuentoTotal)" (click)="elegir('descuentoTotal')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M19 5 5 19" />
          <circle cx="7" cy="7" r="2.5" />
          <circle cx="17" cy="17" r="2.5" />
        </svg>
        <span>Descuento total<small>{{ motivos().descuentoTotal ?? 'Todo el ticket' }}</small></span>
      </button>
      <button type="button" class="accion-hoja" [attr.aria-disabled]="ariaDe(motivos().presupuesto)" (click)="elegir('presupuesto')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M6 3h9l4 4v14H6z" />
          <path d="M9 12h7M9 16h7" />
        </svg>
        <span>Presupuesto<small>{{ motivos().presupuesto ?? 'Guardar sin cobrar' }}</small></span>
      </button>
      <button type="button" class="accion-hoja" [attr.aria-disabled]="ariaDe(motivos().remito)" (click)="elegir('remito')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M3 7h11v9H3zM14 10h4l3 3v3h-7" />
        </svg>
        <span>Remito<small>{{ motivos().remito ?? 'Descuenta stock, no cobra' }}</small></span>
      </button>
      <button type="button" class="accion-hoja peligro" [attr.aria-disabled]="ariaDe(motivos().vaciar)" (click)="elegir('vaciar')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
        </svg>
        <span>Vaciar ticket@if (motivos().vaciar; as motivo) {<small>{{ motivo }}</small>}</span>
      </button>
    </app-hoja-inferior>
  `,
})
export class HojaMasAcciones {
  /** Cuántos renglones tiene el ticket. */
  readonly renglones = input.required<number>();
  /** Lo que se cobraría hoy, en centavos. */
  readonly totalCentavos = input.required<number>();
  /** El cliente elegido para la venta; null es Consumidor Final. */
  readonly cliente = input<string | null>(null);
  /** Por qué no se puede usar cada acción ahora; la que no figura se puede (la misma tabla que apaga la botonera). */
  readonly motivos = input<Partial<Record<AccionBotonera, string>>>({});

  readonly accion = output<AccionBotonera>();
  readonly cerrar = output<void>();

  protected readonly ariaDe = ariaDe;
  /** «3 renglones · $ 8.272,00». */
  protected readonly subtitulo = computed(
    () =>
      `${this.renglones()} ${this.renglones() === 1 ? 'renglón' : 'renglones'} · ${formatearMonto(this.totalCentavos() / 100)}`,
  );

  /** Una acción apagada no avisa nada: la hoja ya dice por qué, y quedarse abierta es lo que el vendedor espera. */
  protected elegir(accion: AccionBotonera): void {
    if (this.motivos()[accion] != null) return;
    this.accion.emit(accion);
  }
}
