import { Signal, signal } from '@angular/core';

/** Cuánto queda a la vista el motivo de una acción que no se pudo usar (el mismo tiempo que en el mostrador). */
export const MOTIVO_A_LA_VISTA_MS = 5000;

/**
 * Para la plantilla: `[attr.aria-disabled]="ariaDe(motivoEditar())"`. Un `false` escrito en el atributo lo deja como el
 * string «false» (que sigue apagando el botón), así que el botón prendido tiene que llevar `null` para no tener atributo.
 */
export const ariaDe = (motivo: string | null): 'true' | null => (motivo === null ? null : 'true');

/**
 * S4: una acción de la barra que no se puede usar explica el motivo al intentarla, en vez de quedar apagada sin decir
 * por qué. El botón lleva `aria-disabled` —así el clic llega igual— y el texto se anuncia con `role="status"`.
 *
 *   protected readonly motivo = new MotivoDeAccion();
 *   protected readonly motivos = computed(() => ({ editar: this.filas.elegido() ? null : MOTIVO_SIN_ELEGIR }));
 *
 *   protected editar(rubro: Rubro | null = this.filas.elegido()): void {
 *     if (this.motivo.mostrar(rubro === null ? MOTIVO_SIN_ELEGIR : null)) return;
 *     ...
 *   }
 *
 * Mientras el botón no tiene motivo, `mostrar(null)` devuelve false y la acción sigue su curso: la regla y su
 * explicación viven en el mismo lugar, así que no pueden quedar desalineadas.
 */
export class MotivoDeAccion {
  private readonly texto = signal<string | null>(null);
  /** El motivo a la vista, o null. La plantilla lo pinta en un `<p>` con `role="status"`. */
  readonly visible: Signal<string | null> = this.texto.asReadonly();
  private reloj?: ReturnType<typeof setTimeout>;

  /** Muestra el motivo y devuelve true (la acción no sigue). Sin motivo devuelve false y no toca lo que se ve. */
  mostrar(motivo: string | null | undefined): boolean {
    if (!motivo) return false;
    this.texto.set(motivo);
    clearTimeout(this.reloj);
    this.reloj = setTimeout(() => this.texto.set(null), MOTIVO_A_LA_VISTA_MS);
    return true;
  }

  /** Lo saca de la vista antes de que se vaya solo (por ejemplo, al volver a la lista después de usarla). */
  limpiar(): void {
    clearTimeout(this.reloj);
    this.texto.set(null);
  }
}
