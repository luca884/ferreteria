import { Component, ElementRef, afterNextRender, computed, input, output, signal, viewChild } from '@angular/core';
import { Unidad } from '../articulos/articulo.models';
import { parsearCantidad } from './ticket';
import { GuardaLector } from './guarda-lector';

/**
 * Cambia la cantidad de un renglón (F3). Presentacional: recibe el renglón y emite la cantidad en
 * milésimas. Tiene el foco propio; la validación es la de R-007.
 */
@Component({
  selector: 'app-dialogo-cantidad',
  template: `
    <div class="fondo">
      <div
        class="dialogo"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-cantidad-titulo"
        (keydown)="alTeclear($event)"
      >
        <h2 id="dialogo-cantidad-titulo">Cantidad de {{ descripcion() }}</h2>
        <input
          #campo
          type="text"
          inputmode="decimal"
          autocomplete="off"
          aria-label="Cantidad"
          [value]="texto()"
          (input)="alEscribir($any($event.target).value)"
        />
        @if (error(); as mensaje) {
          <p class="error" role="alert">{{ mensaje }}</p>
        }
        <div class="acciones">
          <button type="button" class="cancelar" (click)="cancelar.emit()">Cancelar</button>
          <button type="button" class="aceptar" (click)="aceptar()">Aceptar</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .fondo {
      position: fixed;
      inset: 0;
      display: grid;
      place-items: center;
      background: rgb(0 0 0 / 0.4);
      z-index: 20;
    }
    .dialogo {
      background: var(--superficie);
      border-radius: 10px;
      padding: 20px;
      min-width: 320px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    h2 {
      margin: 0;
      font-size: 1.1rem;
    }
    input {
      font: inherit;
      font-size: 1.4rem;
      padding: 8px;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .error {
      margin: 0;
      color: #b00020;
    }
    .acciones {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
  `,
})
export class DialogoCantidad {
  readonly descripcion = input.required<string>();
  readonly unidad = input.required<Unidad>();
  readonly cantidadMilesimas = input.required<number>();

  readonly confirmar = output<number>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly guarda = new GuardaLector();
  private readonly escrito = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly inicial = computed(() => String(this.cantidadMilesimas() / 1000).replace('.', ','));
  protected readonly texto = computed(() => this.escrito() ?? this.inicial());

  constructor() {
    afterNextRender(() => this.enfocar());
  }

  private enfocar(): void {
    const input = this.campo().nativeElement;
    input.focus();
    input.select();
  }

  protected alEscribir(valor: string): void {
    this.escrito.set(valor);
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
    const milesimas = parsearCantidad(texto);
    if (milesimas === null) {
      this.error.set(
        /^\s*\d+[.,]\d{4,}\s*$/.test(texto)
          ? 'Usá hasta 3 decimales.'
          : 'Escribí una cantidad, por ejemplo 2 o 1,5.',
      );
      return;
    }
    if (!(milesimas > 0)) {
      this.error.set('La cantidad tiene que ser mayor que 0.');
      return;
    }
    if (this.unidad() === 'UNIDAD' && milesimas % 1000 !== 0) {
      this.error.set('Este artículo se vende por unidad: usá un número entero.');
      return;
    }
    this.error.set(null);
    this.confirmar.emit(milesimas);
  }
}
