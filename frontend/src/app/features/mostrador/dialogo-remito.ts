import { Component, ElementRef, afterNextRender, effect, input, output, signal, viewChild } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { TrampaFoco } from '../../shared/trampa-foco';
import { CLIENTE_REMITO_MAXIMO, DatosRemito, OBSERVACIONES_REMITO_MAXIMO } from '../remitos/remito.models';
import { GuardaLector } from './guarda-lector';

/**
 * Emitir el ticket como remito. Presentacional: el contenedor le da el total y los renglones para la cabecera y recibe
 * el cliente y las observaciones, los dos opcionales. Enter emite desde cualquier campo y Esc cierra. Mientras `enVuelo`
 * (o después de confirmar, hasta que termine) ignora Enter.
 */
@Component({
  selector: 'app-dialogo-remito',
  imports: [MontoPipe, TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo remito"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-remito-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        <div class="cabecera">
          <h2 id="dialogo-remito-titulo">Emitir remito</h2>
          <p class="resumen">
            @if (renglones(); as n) {
              {{ n }} {{ n === 1 ? 'renglón' : 'renglones' }} ·
            }
            <strong>{{ totalCentavos() / 100 | monto }}</strong>
          </p>
        </div>
        <p class="nota">El remito descuenta el stock ahora y no cobra: se factura después desde Remitos.</p>
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
        <div class="dialogo-acciones">
          <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <button type="button" class="aceptar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="emitir()">
            Emitir
          </button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .remito {
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
    .nota {
      margin: 0;
      color: var(--texto-suave);
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
  `,
})
export class DialogoRemito {
  /** El total del ticket, en centavos, para la cabecera. */
  readonly totalCentavos = input.required<number>();
  /** Cuántos renglones se emiten, para la cabecera. Sin dato no se muestra. */
  readonly renglones = input<number | null>(null);
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** Mensaje del contenedor (por ejemplo, un precio que cambió en el servidor). */
  readonly aviso = input<string | null>(null);
  /** El cliente que el ticket ya tiene (F9, CL-005): si hay uno, reemplaza al texto libre y viaja con el ticket. */
  readonly clienteElegido = input<string | null>(null);

  readonly confirmar = output<DatosRemito>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  protected readonly clienteMaximo = CLIENTE_REMITO_MAXIMO;
  protected readonly observacionesMaximo = OBSERVACIONES_REMITO_MAXIMO;

  /** El campo de texto libre: no está si el ticket ya tiene un cliente elegido. */
  private readonly cliente = viewChild<ElementRef<HTMLInputElement>>('cliente');
  private readonly observaciones = viewChild.required<ElementRef<HTMLInputElement>>('observaciones');
  private readonly guarda = new GuardaLector();
  private readonly bloqueado = signal(false);
  private estuvoEnVuelo = false;

  constructor() {
    afterNextRender(() => (this.cliente() ?? this.observaciones()).nativeElement.focus());
    // Si el POST termina con un error el diálogo sigue abierto: se puede volver a emitir.
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
      (evento.target as HTMLInputElement).value = '';
      this.escaneoDescartado.emit();
      return;
    }
    this.emitir();
  }

  protected emitir(): void {
    if (this.enVuelo() || this.bloqueado()) return;
    const clienteNombre = this.cliente()?.nativeElement.value.trim() ?? '';
    const observaciones = this.observaciones().nativeElement.value.trim();
    this.bloqueado.set(true);
    this.confirmar.emit({
      ...(clienteNombre ? { clienteNombre } : {}),
      ...(observaciones ? { observaciones } : {}),
    });
  }
}
