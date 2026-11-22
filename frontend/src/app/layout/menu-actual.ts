import { Injectable, computed, inject, signal } from '@angular/core';
import { AuthService } from '../core/auth/auth.service';
import { DISPOSICION_ORIGINAL, Disposicion, GrupoMenu, Ubicacion, armarMenu, ubicar as ubicarEn } from './menu';

/**
 * El menú que ve el usuario logueado: la disposición vigente (CU-17) armada para su rol. Es la única fuente: el menú
 * lateral, la barra del celular, el buscador y el hub leen de acá, así nunca muestran cosas distintas.
 *
 * Hasta que el servidor entregue una disposición (T3) rige la original, que es el menú de siempre.
 */
@Injectable({ providedIn: 'root' })
export class MenuActual {
  private readonly auth = inject(AuthService);

  readonly disposicion = signal<Disposicion>(DISPOSICION_ORIGINAL);

  /** Los grupos del rol, con solo lo que puede abrir. */
  readonly grupos = computed<readonly GrupoMenu[]>(() => armarMenu(this.disposicion(), this.auth.esDueno()));

  /**
   * Para ubicar una URL se mira el menú completo, también las pantallas de solo Dueño: ubicar una pantalla no da
   * acceso a nada y así su tono y su grupo no dependen del rol.
   */
  private readonly completo = computed(() => armarMenu(this.disposicion(), true));

  ubicar(url: string): Ubicacion | null {
    return ubicarEn(url, this.completo());
  }
}
