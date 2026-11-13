import { DestroyRef, Signal, computed, inject, signal } from '@angular/core';

/** Cuánto queda a la vista el motivo de una acción que no se pudo usar (el mismo tiempo que en el mostrador). */
export const MOTIVO_A_LA_VISTA_MS = 5000;

/** Los motivos por los que las acciones de una pantalla no se pueden usar, por acción; null si esa acción se puede. */
export type Motivos<A extends string = string> = Readonly<Partial<Record<A, string | null>>>;

/**
 * Para la plantilla: `[attr.aria-disabled]="ariaDe(motivos().editar)"`. Un `false` escrito en el atributo queda como el
 * string «false» (que igual apaga el botón), así que un botón prendido tiene que llevar `null` para no tener atributo.
 */
export const ariaDe = (motivo: string | null | undefined): 'true' | null => (motivo == null ? null : 'true');

/**
 * S4: una acción de la barra que no se puede usar explica el motivo al intentarla, en vez de quedar apagada sin decir
 * por qué. El botón lleva `aria-disabled` —así el clic llega igual— y el texto se anuncia con `role="status"`.
 *
 * Los motivos son una sola tabla: la misma que apaga el botón es la que se explica, así que la regla y su texto no
 * pueden quedar desalineados.
 *
 *   protected readonly motivos = computed(() => ({ editar: this.filas.elegido() ? null : MOTIVO_SIN_ELEGIR }));
 *   protected readonly motivo = new MotivoDeAccion(() => this.motivos());
 *
 *   protected editar(rubro: Rubro | null = this.filas.elegido()): void {
 *     if (!rubro) {
 *       this.motivo.mostrar('editar');
 *       return;
 *     }
 *     ...
 *   }
 *
 * `mostrar` devuelve false cuando la acción sí se puede usar, así que la acción sigue su curso sin preguntar dos veces.
 * Y el texto deja de verse apenas la acción se habilita (por ejemplo al elegir una fila), aunque todavía no se haya
 * usado: el motivo se muestra mientras sea cierto, no mientras dure el reloj.
 */
export class MotivoDeAccion<A extends string = string> {
  private readonly actual = signal<A | null>(null);
  private readonly motivos: () => Motivos<A>;
  private reloj?: ReturnType<typeof setTimeout>;

  /** El texto a la vista, o null. Sale de la tabla, no de lo guardado al intentar: si la acción sigue bloqueada pero por otro motivo, se ve el motivo nuevo. */
  readonly visible: Signal<string | null>;

  constructor(motivos: () => Motivos<A>) {
    this.motivos = motivos;
    this.visible = computed(() => {
      const accion = this.actual();
      return accion === null ? null : (this.motivos()[accion] ?? null);
    });
    // El reloj puede estar pendiente al cerrar la pantalla: se cancela con el componente (S4).
    inject(DestroyRef).onDestroy(() => clearTimeout(this.reloj));
  }

  /** Muestra el motivo de `accion` y devuelve true (la acción no sigue). Sin motivo devuelve false y no toca lo que se ve. */
  mostrar(accion: A): boolean {
    if ((this.motivos()[accion] ?? null) === null) return false;
    this.actual.set(accion);
    clearTimeout(this.reloj);
    this.reloj = setTimeout(() => this.actual.set(null), MOTIVO_A_LA_VISTA_MS);
    return true;
  }

  /** Lo saca de la vista antes de que se vaya solo (por ejemplo, al volver a la lista después de usarla). */
  limpiar(): void {
    clearTimeout(this.reloj);
    this.actual.set(null);
  }
}
