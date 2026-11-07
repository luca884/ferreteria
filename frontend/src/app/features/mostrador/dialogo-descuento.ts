import { Component, ElementRef, afterNextRender, computed, input, output, signal, viewChild } from '@angular/core';
import { TrampaFoco } from '../../shared/trampa-foco';
import { GuardaLector } from './guarda-lector';
import { descuentoEfectivoBp, parsearDescuento } from './ticket';

const DESCUENTO_MAXIMO_BP = 9999;

/**
 * Descuento en % de un renglón (F7) o de todo el ticket (F6). Presentacional: el contenedor le da el título y el
 * descuento actual y recibe el nuevo en centésimas de punto porcentual (1050 = 10,5 %). 0 saca el descuento.
 */
@Component({
  selector: 'app-dialogo-descuento',
  imports: [TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-descuento-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        <h2 id="dialogo-descuento-titulo">{{ titulo() }}</h2>
        <input
          #campo
          type="text"
          inputmode="decimal"
          autocomplete="off"
          spellcheck="false"
          aria-label="Descuento en porcentaje"
          [attr.aria-describedby]="error() ? 'dialogo-descuento-ayuda dialogo-descuento-error' : 'dialogo-descuento-ayuda'"
          [attr.aria-invalid]="error() ? 'true' : null"
          [value]="texto()"
          (input)="escrito.set($any($event.target).value)"
        />
        <p id="dialogo-descuento-ayuda" class="ayuda">
          Porcentaje de 0 a 99,99. Con 0 lo saca.
          @if (topeTexto(); as tope) {
            Tu tope es {{ tope }} %.
          }
        </p>
        @if (error(); as mensaje) {
          <p id="dialogo-descuento-error" class="error" role="alert">{{ mensaje }}</p>
        }
        <div class="dialogo-acciones">
          <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <button type="button" class="aceptar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="aceptar()">
            Aceptar
          </button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .ayuda {
      margin: 0;
      color: var(--texto-suave);
      font-weight: 600;
    }
    input {
      height: 60px;
      font-size: 2rem;
      font-weight: 700;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class DialogoDescuento {
  readonly titulo = input.required<string>();
  /** El descuento que tiene hoy, en centésimas de punto porcentual. */
  readonly descuentoBp = input.required<number>();
  /** CU-07: el tope del usuario en centésimas; null = sin tope (el servidor igual lo controla). */
  readonly topeBp = input<number | null>(null);
  /**
   * El otro descuento que se suma a este, en centésimas: en F6 el mayor de los renglones; en F7 el general. Con tope, se
   * avisa si la combinación lo pasa, como el servidor. 0 = no combina (por ejemplo, un ticket de un presupuesto).
   */
  readonly otroDescuentoBp = input(0);
  /** De dónde es el otro descuento, para el texto del aviso. */
  readonly otroDescuentoEs = input<'renglon' | 'general'>('general');

  readonly confirmar = output<number>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly guarda = new GuardaLector();
  protected readonly escrito = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly inicial = computed(() => String(this.descuentoBp() / 100).replace('.', ','));
  protected readonly texto = computed(() => this.escrito() ?? this.inicial());
  protected readonly topeTexto = computed(() => {
    const tope = this.topeBp();
    return tope === null ? null : String(tope / 100).replace('.', ',');
  });

  constructor() {
    afterNextRender(() => this.enfocar());
  }

  private enfocar(): void {
    const input = this.campo().nativeElement;
    input.focus();
    input.select();
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
      this.escrito.set(null);
      this.error.set(null);
      this.campo().nativeElement.value = this.inicial();
      this.enfocar();
      this.escaneoDescartado.emit();
      return;
    }
    this.aceptar();
  }

  protected aceptar(): void {
    const texto = this.campo().nativeElement.value;
    const bp = parsearDescuento(texto);
    if (bp === null) {
      this.rechazar(
        /^\s*\d+[.,]\d{3,}\s*$/.test(texto) ? 'Usá hasta 2 decimales.' : 'Escribí un porcentaje, por ejemplo 10 o 7,5.',
      );
      return;
    }
    if (bp > DESCUENTO_MAXIMO_BP) {
      this.rechazar('El descuento tiene que estar entre 0 y 99,99 %.');
      return;
    }
    const tope = this.topeBp();
    if (tope !== null && bp > tope) {
      this.rechazar(`Tu tope de descuento es ${this.topeTexto()} %.`);
      return;
    }
    if (tope !== null && bp > 0 && descuentoEfectivoBp(bp, this.otroDescuentoBp()) > tope) {
      const otro = this.otroDescuentoEs() === 'renglon' ? 'del renglón' : 'general';
      this.rechazar(`Sumado al descuento ${otro}, pasa tu tope de descuento de ${this.topeTexto()} %.`);
      return;
    }
    this.error.set(null);
    this.confirmar.emit(bp);
  }

  /** Muestra el error y deja el foco en el campo. */
  private rechazar(mensaje: string): void {
    this.error.set(mensaje);
    this.campo().nativeElement.focus();
  }
}
