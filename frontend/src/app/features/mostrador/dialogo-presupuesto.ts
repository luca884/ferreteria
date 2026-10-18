import { Component, ElementRef, afterNextRender, effect, input, output, signal, viewChild } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { TrampaFoco } from '../../shared/trampa-foco';
import {
  CLIENTE_MAXIMO,
  DIAS_VALIDEZ_MAXIMOS,
  DIAS_VALIDEZ_POR_DEFECTO,
  DatosPresupuesto,
  OBSERVACIONES_MAXIMO,
} from '../presupuestos/presupuesto.models';
import { GuardaLector } from './guarda-lector';

const MENSAJE_VALIDEZ = `La validez va de 1 a ${DIAS_VALIDEZ_MAXIMOS} días.`;

/**
 * Guardar el ticket como presupuesto (F8). Presentacional: el contenedor le da el total y los renglones para la
 * cabecera y recibe el cliente (opcional), los días de validez y las observaciones (opcionales). Enter guarda desde
 * cualquier campo y Esc cierra. Mientras `enVuelo` (o después de confirmar, hasta que termine) ignora Enter.
 */
@Component({
  selector: 'app-dialogo-presupuesto',
  imports: [MontoPipe, TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo presupuesto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-presupuesto-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        <div class="cabecera">
          <h2 id="dialogo-presupuesto-titulo">Guardar presupuesto</h2>
          <p class="resumen">
            @if (renglones(); as n) {
              {{ n }} {{ n === 1 ? 'renglón' : 'renglones' }} ·
            }
            <strong>{{ totalCentavos() / 100 | monto }}</strong>
          </p>
        </div>
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        <label class="campo">
          Cliente <small>(opcional)</small>
          <input
            #cliente
            name="cliente"
            type="text"
            autocomplete="off"
            spellcheck="false"
            [maxLength]="clienteMaximo"
          />
        </label>
        <label class="campo corto">
          Validez en días
          <input
            #validez
            name="validez"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            spellcheck="false"
            [value]="diasPorDefecto"
            [attr.aria-invalid]="error() ? 'true' : null"
            [attr.aria-describedby]="error() ? 'dialogo-presupuesto-error' : null"
            (input)="error.set(null)"
          />
        </label>
        <label class="campo">
          Observaciones <small>(opcional)</small>
          <input
            #observaciones
            name="observaciones"
            type="text"
            autocomplete="off"
            spellcheck="false"
            [maxLength]="observacionesMaximo"
          />
        </label>
        @if (error(); as mensaje) {
          <p id="dialogo-presupuesto-error" class="error" role="alert">{{ mensaje }}</p>
        }
        <div class="dialogo-acciones">
          <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <button type="button" class="aceptar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="guardar()">
            Guardar
          </button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .presupuesto {
      width: min(520px, 100%);
      gap: var(--e-3);
    }
    .cabecera {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--e-4);
    }
    .resumen {
      margin: 0;
      color: var(--texto-suave);
      font-weight: 600;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    }
    .resumen strong {
      color: var(--texto);
    }
    .aviso {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--advertencia-borde);
      border-left: 4px solid var(--advertencia-marca);
      border-radius: var(--radio);
      background: var(--advertencia-fondo);
      font-weight: 600;
    }
    .campo {
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-weight: 600;
    }
    .campo small {
      color: var(--texto-tenue);
      font-weight: 500;
    }
    .corto input {
      max-width: 8rem;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class DialogoPresupuesto {
  /** El total del ticket, en centavos, para la cabecera. */
  readonly totalCentavos = input.required<number>();
  /** Cuántos renglones se guardan, para la cabecera. Sin dato no se muestra. */
  readonly renglones = input<number | null>(null);
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** Mensaje del contenedor (por ejemplo, un precio que cambió en el servidor). */
  readonly aviso = input<string | null>(null);

  readonly confirmar = output<DatosPresupuesto>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  protected readonly clienteMaximo = CLIENTE_MAXIMO;
  protected readonly observacionesMaximo = OBSERVACIONES_MAXIMO;
  protected readonly diasPorDefecto = String(DIAS_VALIDEZ_POR_DEFECTO);
  protected readonly error = signal<string | null>(null);

  private readonly cliente = viewChild.required<ElementRef<HTMLInputElement>>('cliente');
  private readonly validez = viewChild.required<ElementRef<HTMLInputElement>>('validez');
  private readonly observaciones = viewChild.required<ElementRef<HTMLInputElement>>('observaciones');
  private readonly guarda = new GuardaLector();
  private readonly bloqueado = signal(false);
  private estuvoEnVuelo = false;

  constructor() {
    afterNextRender(() => this.cliente().nativeElement.focus());
    // Si el POST termina con un error el diálogo sigue abierto: se puede volver a guardar.
    effect(() => {
      if (this.enVuelo()) this.estuvoEnVuelo = true;
      else if (this.estuvoEnVuelo) {
        this.estuvoEnVuelo = false;
        this.bloqueado.set(false);
      }
    });
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cancelar.emit();
      return;
    }
    const eraEscaneo = this.guarda.alTeclear(evento);
    if (evento.key !== 'Enter') return;
    evento.preventDefault();
    evento.stopPropagation();
    if (eraEscaneo) {
      this.vaciarCampo(evento.target as HTMLInputElement);
      this.escaneoDescartado.emit();
      return;
    }
    this.guardar();
  }

  /** El escaneo escribió en un campo: vuelve a lo de antes de abrir (vacío, o los 15 días de la validez). */
  private vaciarCampo(campo: HTMLInputElement): void {
    campo.value = campo === this.validez().nativeElement ? this.diasPorDefecto : '';
    this.error.set(null);
  }

  protected guardar(): void {
    if (this.enVuelo() || this.bloqueado()) return;
    const dias = /^\s*\d+\s*$/.test(this.validez().nativeElement.value)
      ? Number(this.validez().nativeElement.value)
      : NaN;
    if (!(dias >= 1 && dias <= DIAS_VALIDEZ_MAXIMOS)) {
      this.error.set(MENSAJE_VALIDEZ);
      this.validez().nativeElement.focus();
      return;
    }
    this.error.set(null);
    const clienteNombre = this.cliente().nativeElement.value.trim();
    const observaciones = this.observaciones().nativeElement.value.trim();
    this.bloqueado.set(true);
    this.confirmar.emit({
      diasValidez: dias,
      ...(clienteNombre ? { clienteNombre } : {}),
      ...(observaciones ? { observaciones } : {}),
    });
  }
}
