import { Component, ElementRef, input, output, signal, viewChild } from '@angular/core';

/**
 * Elegir el archivo de la lista sin el input nativo (que sale en inglés): botón «Elegir archivo…»,
 * nombre del archivo y zona para arrastrar y soltar. El input real queda oculto detrás del botón.
 */
@Component({
  selector: 'app-selector-archivo',
  template: `
    <span class="rotulo" id="rotulo-archivo">Archivo de la lista</span>
    <div
      class="zona-archivo"
      role="group"
      aria-labelledby="rotulo-archivo"
      [class.arrastrando]="arrastrando()"
      (dragover)="sobre($event)"
      (dragleave)="arrastrando.set(false)"
      (drop)="soltar($event)"
    >
      <button type="button" class="elegir-archivo secundario" (click)="abrir()">Elegir archivo…</button>
      <span class="nombre-archivo" aria-live="polite">{{ archivo()?.name ?? 'Ningún archivo elegido' }}</span>
      <input
        #entrada
        id="archivo"
        type="file"
        hidden
        accept=".xlsx,.xls,.csv"
        (change)="elegir()"
      />
    </div>
    <span class="ayuda">Excel (.xlsx, .xls) o CSV, hasta 25&nbsp;MB. También podés arrastrarlo hasta acá.</span>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 180px; }
    .rotulo { font-weight: 600; }
    .ayuda { color: var(--texto-suave); }
    .zona-archivo {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 12px;
      border: 2px dashed var(--borde);
      border-radius: 8px;
      background: var(--superficie);
    }
    .zona-archivo.arrastrando { border-color: var(--acento); background: var(--acento-suave); }
    .nombre-archivo { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,
})
export class SelectorArchivo {
  readonly archivo = input<File | null>(null);
  readonly elegido = output<File | null>();

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
    evento.preventDefault();
    this.arrastrando.set(true);
  }

  protected soltar(evento: DragEvent): void {
    evento.preventDefault();
    this.arrastrando.set(false);
    const archivo = evento.dataTransfer?.files?.[0];
    if (archivo) this.elegido.emit(archivo);
  }
}
