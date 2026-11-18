import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, afterNextRender, computed, input, output, signal, viewChild } from '@angular/core';
import { Unidad } from '../articulos/articulo.models';
import { parsearCantidad } from './ticket';
import { HojaInferior } from '../../shared/hoja-inferior';
import { esCelular } from '../../shared/es-celular';
import { TrampaFoco } from '../../shared/trampa-foco';
import { GuardaLector } from './guarda-lector';

const UNIDADES_PLURAL: Record<Unidad, string> = {
  UNIDAD: 'Unidades',
  METRO: 'Metros',
  KILO: 'Kilos',
  LITRO: 'Litros',
};

/**
 * Cambia la cantidad de un renglón (F3). Presentacional: recibe el renglón y emite la cantidad en
 * milésimas. Tiene el foco propio; la validación es la de R-007.
 */
@Component({
  selector: 'app-dialogo-cantidad',
  imports: [NgTemplateOutlet, TrampaFoco, HojaInferior],
  template: `
    @if (celular) {
      <!-- movil-sectores T8a: en el celular la cantidad es una hoja inferior con un paso de − y + a los costados del campo. -->
      <app-hoja-inferior [titulo]="'Cantidad de ' + descripcion()" (cerrar)="cancelar.emit()">
        <div class="cuerpo-hoja" (keydown)="alTeclear($event)">
          <ng-container [ngTemplateOutlet]="ayudaTpl" />
          <div class="paso">
            <button type="button" class="paso-boton" aria-label="Restar uno" (click)="mover(-1)">−</button>
            <ng-container [ngTemplateOutlet]="entradaTpl" />
            <button type="button" class="paso-boton" aria-label="Sumar uno" (click)="mover(1)">+</button>
          </div>
          <ng-container [ngTemplateOutlet]="errorTpl" />
          <ng-container [ngTemplateOutlet]="accionesTpl" />
        </div>
      </app-hoja-inferior>
    } @else {
      <div class="dialogo-fondo">
        <div
          class="dialogo"
          role="dialog"
          aria-modal="true"
          aria-labelledby="dialogo-cantidad-titulo"
          appTrampaFoco
          (keydown)="alTeclear($event)"
        >
          <h2 id="dialogo-cantidad-titulo">Cantidad de {{ descripcion() }}</h2>
          <ng-container [ngTemplateOutlet]="entradaTpl" />
          <ng-container [ngTemplateOutlet]="ayudaTpl" />
          <ng-container [ngTemplateOutlet]="errorTpl" />
          <ng-container [ngTemplateOutlet]="accionesTpl" />
        </div>
      </div>
    }

    <ng-template #entradaTpl>
      <input
        #campo
        type="text"
        inputmode="decimal"
        autocomplete="off"
        spellcheck="false"
        aria-label="Cantidad"
        [attr.aria-describedby]="error() ? 'dialogo-cantidad-ayuda dialogo-cantidad-error' : 'dialogo-cantidad-ayuda'"
        [attr.aria-invalid]="error() ? 'true' : null"
        [value]="texto()"
        (input)="alEscribir($any($event.target).value)"
      />
    </ng-template>
    <ng-template #ayudaTpl>
      <p id="dialogo-cantidad-ayuda" class="ayuda">{{ ayuda() }}</p>
    </ng-template>
    <ng-template #errorTpl>
      @if (error(); as mensaje) {
        <p id="dialogo-cantidad-error" class="error" role="alert">{{ mensaje }}</p>
      }
    </ng-template>
    <ng-template #accionesTpl>
      <div class="dialogo-acciones">
        <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
          Cancelar
        </button>
        <button type="button" class="aceptar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="aceptar()">
          Aceptar
        </button>
      </div>
    </ng-template>
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
    /* La hoja del celular: la ayuda arriba, el campo entre el − y el +, y los dos botones a lo ancho (artboard 02). */
    .cuerpo-hoja {
      display: flex;
      flex-direction: column;
      gap: var(--e-3);
    }
    .cuerpo-hoja .ayuda {
      margin-top: 2px;
      font-weight: 400;
      font-size: var(--texto-sm);
    }
    .paso {
      display: grid;
      grid-template-columns: 52px minmax(0, 1fr) 52px;
      gap: var(--e-2);
    }
    .paso input,
    .paso-boton {
      min-width: 0;
      height: 52px;
      min-height: 52px;
    }
    .paso input {
      font-family: var(--fuente-mono);
      font-size: 1.375rem;
      text-align: center;
    }
    .paso-boton {
      padding: 0;
      border-radius: var(--radio-lg);
      font-size: 1.375rem;
      line-height: 1;
    }
    .cuerpo-hoja .dialogo-acciones {
      position: static;
      flex-wrap: nowrap;
    }
    .cuerpo-hoja .dialogo-acciones button {
      flex: 1 1 0;
      min-height: 52px;
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

  /** En qué se mide el renglón y qué cantidades acepta, para no tener que probar y fallar. */
  protected readonly ayuda = computed(() => `${UNIDADES_PLURAL[this.unidad()]} — ${this.unidad() === 'UNIDAD' ? 'solo números enteros' : 'acepta hasta 3 decimales'}`);

  protected readonly inicial = computed(() => String(this.cantidadMilesimas() / 1000).replace('.', ','));
  protected readonly texto = computed(() => this.escrito() ?? this.inicial());

  /** En el celular es una hoja inferior con un paso de − y +; en el escritorio y la tablet, el diálogo de siempre. */
  protected readonly celular = esCelular();

  constructor() {
    // En el celular no se enfoca el campo: abriría el teclado encima del paso, que es lo que se toca.
    if (!this.celular) afterNextRender(() => this.enfocar());
  }

  private enfocar(): void {
    const input = this.campo().nativeElement;
    input.focus();
    input.select();
  }

  /** El paso del celular: suma o resta uno a lo que está escrito (o a la cantidad del renglón si no es un número) sin bajar de 0. */
  protected mover(delta: 1 | -1): void {
    const actual = parsearCantidad(this.texto()) ?? this.cantidadMilesimas();
    const nueva = actual + delta * 1000;
    if (nueva < 1) return;
    this.escrito.set(String(nueva / 1000).replace('.', ','));
    this.error.set(null);
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
      this.rechazar(
        /^\s*\d+[.,]\d{4,}\s*$/.test(texto)
          ? 'Usá hasta 3 decimales.'
          : 'Escribí una cantidad, por ejemplo 2 o 1,5.',
      );
      return;
    }
    if (!(milesimas > 0)) {
      this.rechazar('La cantidad tiene que ser mayor que 0.');
      return;
    }
    if (this.unidad() === 'UNIDAD' && milesimas % 1000 !== 0) {
      this.rechazar('Este artículo se vende por unidad: usá un número entero.');
      return;
    }
    this.error.set(null);
    this.confirmar.emit(milesimas);
  }

  /** Muestra el error y deja el foco en el campo (con el botón «Aceptar» estaba afuera). */
  private rechazar(mensaje: string): void {
    this.error.set(mensaje);
    this.campo().nativeElement.focus();
  }
}
