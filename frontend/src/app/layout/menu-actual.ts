import { Injectable, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, filter, map, of, take, timeout } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { MenuService } from '../core/menu/menu.service';
import { DISPOSICION_ORIGINAL, GrupoMenu, ID_GRUPO_VENTAS, Ubicacion, armarMenu, ubicar as ubicarEn } from './menu';

/**
 * El menú que ve el usuario logueado: la disposición vigente (CU-17) armada para su rol. Es la única fuente: el menú
 * lateral, la barra del celular, el buscador y el hub leen de acá, así nunca muestran cosas distintas.
 *
 * Mientras se lee por primera vez, si falla o si no hay sesión de ferretería, rige el menú de siempre.
 */
@Injectable({ providedIn: 'root' })
export class MenuActual {
  private readonly auth = inject(AuthService);
  private readonly menus = inject(MenuService);
  private readonly version = signal(0);

  private readonly carga = rxResource({
    params: () => {
      const token = this.auth.token();
      if (!token) return null;
      const sesion = this.auth.sesion();
      if (!sesion || sesion.rol === 'SUPER_ADMIN' || this.auth.debeCambiarContrasena()) return null;
      // Cambiar de sesión descarta su valor anterior y cancela la lectura pendiente. Editar el nombre no recarga.
      return JSON.stringify([token, sesion.userId, sesion.ferreteriaId, sesion.rol, this.version()]);
    },
    defaultValue: DISPOSICION_ORIGINAL,
    // Una sesión no habilitada cancela la lectura anterior sin hacer otro pedido.
    // El límite evita que un enlace directo a un hub quede esperando indefinidamente.
    stream: ({ params }) => params === null
      ? of(DISPOSICION_ORIGINAL)
      : this.menus.obtener().pipe(timeout({ first: 5_000 }), catchError(() => of(DISPOSICION_ORIGINAL))),
  });
  private readonly estado$ = toObservable(this.carga.snapshot);

  readonly disposicion = this.carga.value;

  /** El editor lo llama tras guardar; si había una lectura en curso, vuelve a empezar para no usar su respuesta. */
  recargar(): void {
    if (!this.carga.reload()) this.version.update((version) => version + 1);
  }

  /** Solo los hubs esperan: al abrir un enlace directo, el grupo puede no existir en el menú original. */
  esperarDisposicion(): Observable<void> {
    if (!this.carga.isLoading()) return of(undefined);
    return this.estado$.pipe(filter(() => !this.carga.isLoading()), take(1), map(() => undefined));
  }

  /** Los grupos del rol, con solo lo que puede abrir. */
  readonly grupos = computed<readonly GrupoMenu[]>(() => armarMenu(this.disposicion(), this.auth.esDueno()));

  grupoDelHub(id: string | null): GrupoMenu | undefined {
    return this.grupos().find((grupo) => grupo.id === (id ?? ID_GRUPO_VENTAS));
  }

  /**
   * Para ubicar una URL se mira el menú completo, también las pantallas de solo Dueño: ubicar una pantalla no da
   * acceso a nada y así su tono y su grupo no dependen del rol.
   */
  private readonly completo = computed(() => armarMenu(this.disposicion(), true));

  ubicar(url: string): Ubicacion | null {
    return ubicarEn(url, this.completo());
  }
}
