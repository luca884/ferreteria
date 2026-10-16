import { ElementRef, Injector, afterNextRender, computed, inject, signal } from '@angular/core';

/**
 * Fila elegida de una grilla con barra de acciones arriba (Artículos, Rubros, Proveedores): la barra actúa sobre ella.
 *
 * - Clic o foco en una fila la elige.
 * - ↑/↓ mueven la elección y el foco; Enter llama a `alEnter` (Editar).
 * - Solo la elegida (o la primera, si no hay) entra en el orden de Tab.
 * - Si `habilitado` da false (quien mira no tiene acciones), no se elige nada.
 *
 * Se crea en un campo del componente, porque toma el elemento anfitrión con `inject`:
 *
 *   protected readonly filas = new FilasElegibles(this.rubros, () => this.auth.esDueno(), (r) => this.editar(r));
 *
 * Las filas son los `tbody tr` del componente, en el mismo orden que la lista.
 */
export class FilasElegibles<T extends { id: number }> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** Por id, para que siga elegida si la lista se recarga y la fila sigue ahí. */
  readonly elegidoId = signal<number | null>(null);
  readonly elegido = computed(() => this.lista().find((fila) => fila.id === this.elegidoId()) ?? null);

  constructor(
    private readonly lista: () => readonly T[],
    private readonly habilitado: () => boolean,
    private readonly alEnter: (fila: T) => void,
  ) {}

  elegir(fila: T): void {
    if (this.habilitado()) this.elegidoId.set(fila.id);
  }

  esTabulable(fila: T, indice: number): boolean {
    return this.elegido() ? fila.id === this.elegidoId() : indice === 0;
  }

  alTeclear(evento: KeyboardEvent, fila: T, indice: number): void {
    if (!this.habilitado()) return;
    if (evento.key === 'Enter') {
      evento.preventDefault();
      this.alEnter(fila);
      return;
    }
    const paso = evento.key === 'ArrowDown' ? 1 : evento.key === 'ArrowUp' ? -1 : 0;
    if (paso === 0) return;
    evento.preventDefault();
    const filas = this.lista();
    const destino = Math.min(Math.max(indice + paso, 0), filas.length - 1);
    this.elegidoId.set(filas[destino].id);
    this.enfocar(destino);
  }

  /** Pone el foco en la elegida (por ejemplo, al volver de editarla); sin elegida no hace nada. */
  enfocarElegida(): void {
    const indice = this.lista().findIndex((fila) => fila.id === this.elegidoId());
    if (indice >= 0) this.enfocar(indice);
  }

  /** Después de pintar, enfoca la fila del índice. */
  private enfocar(indice: number): void {
    afterNextRender(
      () => this.host.nativeElement.querySelectorAll<HTMLElement>('tbody tr')[indice]?.focus(),
      { injector: this.injector },
    );
  }
}
