import {
  Component,
  ElementRef,
  afterNextRender,
  input,
  model,
  output,
  viewChild,
} from '@angular/core';
import { DetectorLector } from './detector-lector';

/** Destinos de foco a los que la caja no se lo quita: navegación, botones y otros campos. */
const INTERACTIVOS = 'a, button, input, select, textarea, [contenteditable]';

/** Teclas que no escriben nada: un lector las manda para las mayúsculas y no cortan la ráfaga. */
const MODIFICADORES = ['Shift', 'CapsLock', 'AltGraph'];

/**
 * Campo de búsqueda del mostrador. Tiene el foco siempre, porque el lector de código de barras
 * escribe como un teclado: el vendedor no tiene que hacer clic antes de escanear.
 */
@Component({
  selector: 'app-caja-busqueda',
  templateUrl: './caja-busqueda.html',
  styleUrl: './caja-busqueda.css',
})
export class CajaBusqueda {
  readonly texto = model('');
  /** Id de la lista de resultados que controla el campo (aria-controls). */
  readonly controla = input<string | null>(null);
  /** Id de la fila resaltada (aria-activedescendant). */
  readonly opcionActiva = input<string | null>(null);

  readonly enter = output<void>();
  readonly mover = output<1 | -1>();

  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly detector = new DetectorLector();

  constructor() {
    afterNextRender(() => this.enfocar());
  }

  enfocar(): void {
    this.campo().nativeElement.focus();
  }

  seleccionarTodo(): void {
    this.campo().nativeElement.select();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.isComposing) return;
    if (evento.key !== 'Enter') this.registrarTecla(evento);
    switch (evento.key) {
      case 'Enter':
        evento.preventDefault();
        this.alEnter();
        break;
      case 'ArrowDown':
        evento.preventDefault();
        this.mover.emit(1);
        break;
      case 'ArrowUp':
        evento.preventDefault();
        this.mover.emit(-1);
        break;
    }
  }

  private alEnter(): void {
    // Un escaneo reemplaza el texto previo, aunque lo haya tipeado una persona.
    const escaneado = this.detector.alEnter(Date.now());
    if (escaneado !== null) this.texto.set(escaneado);
    this.enter.emit();
  }

  /** Alimenta al detector: los caracteres suman a la ráfaga; lo que edita el texto la corta. */
  private registrarTecla(evento: KeyboardEvent): void {
    if (MODIFICADORES.includes(evento.key)) return;
    const esCaracter =
      evento.key.length === 1 && !evento.ctrlKey && !evento.metaKey && !evento.altKey;
    if (esCaracter) this.detector.tecla(evento.key, Date.now());
    else this.detector.reiniciar();
  }

  /** Recupera el foco salvo que haya pasado a algo interactivo (un enlace de la barra, «Salir»). */
  protected alPerderFoco(evento: FocusEvent): void {
    const destino = evento.relatedTarget;
    if (destino instanceof Element && destino.closest(INTERACTIVOS)) return;
    setTimeout(() => this.enfocar(), 0);
  }
}
