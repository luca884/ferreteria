import { Component, ElementRef, afterNextRender, effect, input, output, signal, viewChild } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { esCelular } from '../../shared/es-celular';
import { TrampaFoco } from '../../shared/trampa-foco';
import {
  CLIENTE_MAXIMO,
  DIAS_VALIDEZ_POR_DEFECTO,
  DatosPresupuesto,
  OBSERVACIONES_MAXIMO,
} from '../presupuestos/presupuesto.models';
import { GuardaLector } from './guarda-lector';
import { MENSAJE_VALIDEZ, diasDeValidez } from './validez-presupuesto';

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
        [class.presupuesto-celular]="celular"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-presupuesto-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        @if (celular) {
          <!-- movil-sectores T10: en el celular es una pantalla completa: la flecha «Volver» arriba (hace de «Cancelar») y la acción fija abajo. -->
          <div class="ficha-barra">
            <button type="button" class="ficha-volver" aria-label="Volver" (click)="cancelar.emit()">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="m15 6-6 6 6 6" />
              </svg>
            </button>
            <h2 id="dialogo-presupuesto-titulo">Guardar presupuesto</h2>
          </div>
        }
        <div class="cabecera">
          @if (!celular) {
            <h2 id="dialogo-presupuesto-titulo">Guardar presupuesto</h2>
          }
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
        @if (clienteElegido(); as nombre) {
          <p class="cliente-elegido">
            Cliente: <strong>{{ nombre }}</strong>
          </p>
        } @else {
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
        }
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
        <div class="dialogo-acciones" [class.ficha-pie]="celular">
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
    .cliente-elegido {
      margin: 0;
      color: var(--texto-suave);
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
  /** El cliente que el ticket ya tiene (F9, CL-005): si hay uno, reemplaza al texto libre y viaja con el ticket. */
  readonly clienteElegido = input<string | null>(null);

  readonly confirmar = output<DatosPresupuesto>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  /**
   * En el celular es una pantalla completa (movil-sectores T10); en el escritorio y la tablet, el diálogo centrado de
   * siempre. Se lee al abrir: no sigue la rotación, como el cobro y el selector de cliente. Es sólo cómo se ve: el estado, la
   * validación y lo que se confirma son los mismos.
   */
  protected readonly celular = esCelular();

  protected readonly clienteMaximo = CLIENTE_MAXIMO;
  protected readonly observacionesMaximo = OBSERVACIONES_MAXIMO;
  protected readonly diasPorDefecto = String(DIAS_VALIDEZ_POR_DEFECTO);
  protected readonly error = signal<string | null>(null);

  /** El campo de texto libre: no está si el ticket ya tiene un cliente elegido. */
  private readonly cliente = viewChild<ElementRef<HTMLInputElement>>('cliente');
  private readonly validez = viewChild.required<ElementRef<HTMLInputElement>>('validez');
  private readonly observaciones = viewChild.required<ElementRef<HTMLInputElement>>('observaciones');
  private readonly guarda = new GuardaLector();
  private readonly bloqueado = signal(false);
  private estuvoEnVuelo = false;

  constructor() {
    afterNextRender(() => (this.cliente() ?? this.validez()).nativeElement.focus());
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
    const dias = diasDeValidez(this.validez().nativeElement.value);
    if (dias === null) {
      this.error.set(MENSAJE_VALIDEZ);
      this.validez().nativeElement.focus();
      return;
    }
    this.error.set(null);
    const clienteNombre = this.cliente()?.nativeElement.value.trim() ?? '';
    const observaciones = this.observaciones().nativeElement.value.trim();
    this.bloqueado.set(true);
    this.confirmar.emit({
      diasValidez: dias,
      ...(clienteNombre ? { clienteNombre } : {}),
      ...(observaciones ? { observaciones } : {}),
    });
  }
}
