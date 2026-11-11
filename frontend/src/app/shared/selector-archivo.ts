import { Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';

let siguienteId = 0;

/**
 * La pista de arrastre del final de la ayuda. Se separa para que se pueda esconder en una pantalla táctil, donde
 * arrastrar un archivo no existe (responsive, S10). El texto no cambia: la ayuda y la pista se leen seguidas.
 */
const PISTA_ARRASTRE = /También podés arrastrar\w+ hasta acá\.$/;

/**
 * Elegir un archivo sin el input nativo (que sale en inglés): botón «Elegir archivo…», nombre del archivo y zona para
 * arrastrar y soltar. El input real queda oculto detrás del botón. Sin datos propios es el de la lista de precios.
 */
@Component({
  selector: 'app-selector-archivo',
  template: `
    <span class="rotulo" [id]="rotuloId">{{ rotulo() }}</span>
    <div
      class="zona-archivo"
      role="group"
      [attr.aria-labelledby]="rotuloId"
      [class.arrastrando]="arrastrando()"
      [class.deshabilitada]="deshabilitado()"
      [attr.aria-disabled]="deshabilitado()"
      (dragover)="sobre($event)"
      (dragleave)="arrastrando.set(false)"
      (drop)="soltar($event)"
    >
      <button
        type="button"
        class="elegir-archivo secundario"
        [disabled]="deshabilitado()"
        (click)="abrir()"
      >
        Elegir archivo…
      </button>
      <span class="nombre-archivo" aria-live="polite">{{
        archivo()?.name ?? 'Ningún archivo elegido'
      }}</span>
      <input
        #entrada
        [id]="entradaId()"
        type="file"
        hidden
        [disabled]="deshabilitado()"
        [accept]="acepta()"
        (change)="elegir()"
      />
    </div>
    <span class="ayuda">{{ ayudaBase() }}<span class="pista-teclado">{{ pista() }}</span></span>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex: 1;
      min-width: 180px;
    }
    .rotulo {
      font-weight: 600;
    }
    .ayuda {
      color: var(--texto-suave);
    }
    .zona-archivo {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 12px;
      border: 2px dashed var(--borde);
      border-radius: 8px;
      background: var(--superficie);
    }
    .zona-archivo.arrastrando {
      border-color: var(--acento);
      background: var(--acento-suave);
    }
    .zona-archivo.deshabilitada {
      opacity: 0.6;
      cursor: not-allowed;
    }
    .nombre-archivo {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
})
export class SelectorArchivo {
  readonly archivo = input<File | null>(null);
  readonly elegido = output<File | null>();
  /** Mientras se trabaja con el archivo elegido no se elige otro: ni con el botón ni soltando un archivo. */
  readonly deshabilitado = input(false);
  readonly rotulo = input('Archivo de la lista');
  /** El `accept` del input: qué archivos ofrece el navegador. */
  readonly acepta = input('.xlsx,.xls,.csv,.pdf');
  readonly ayuda = input(
    'Excel (.xlsx, .xls), CSV o PDF, hasta 25 MB. También podés arrastrarlo hasta acá.',
  );
  readonly entradaId = input('archivo');

  /** La ayuda sin la pista de arrastre, con el espacio que las separa: las dos juntas dicen lo mismo que `ayuda`. */
  protected readonly ayudaBase = computed(() => this.ayuda().replace(PISTA_ARRASTRE, ''));
  /** La pista de arrastre, si la ayuda la trae: se esconde en táctil por la clase `.pista-teclado` de styles.css. */
  protected readonly pista = computed(() => PISTA_ARRASTRE.exec(this.ayuda())?.[0] ?? '');

  protected readonly rotuloId = `selector-archivo-${++siguienteId}`;
  protected readonly arrastrando = signal(false);
  private readonly entrada = viewChild.required<ElementRef<HTMLInputElement>>('entrada');

  protected abrir(): void {
    this.entrada().nativeElement.click();
  }

  protected elegir(): void {
    const input = this.entrada().nativeElement;
    this.elegido.emit(input.files?.[0] ?? null);
    // El nombre sale del padre; vaciar el input permite volver a elegir el mismo archivo.
    input.value = '';
  }

  protected sobre(evento: DragEvent): void {
    // Aun deshabilitada se evita lo de siempre: que el navegador abra el archivo soltado.
    evento.preventDefault();
    this.arrastrando.set(!this.deshabilitado());
  }

  protected soltar(evento: DragEvent): void {
    evento.preventDefault();
    this.arrastrando.set(false);
    if (this.deshabilitado()) return;
    const archivo = evento.dataTransfer?.files?.[0];
    if (archivo) this.elegido.emit(archivo);
  }
}
