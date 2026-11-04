import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { EMPTY, catchError, filter, interval, merge, pairwise, startWith, switchMap } from 'rxjs';
import { ROL_LABELS } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../core/cotizacion/cotizacion.service';
import { BuscadorMenu } from './buscador-menu';
import { CotizacionLateral } from './cotizacion-lateral';
import { IconoDelMenu } from './icono-menu';
import { MOSTRADOR, Seccion, gruposPara, seccionDe } from './menu';

/** Cada cuánto vuelve a leer la cotización del servidor. Solo lee la base: no llama a ninguna API externa. */
export const INTERVALO_COTIZACION_MS = 5 * 60_000;

/** «Juan Pérez» → «JP», «ana» → «A». */
export function iniciales(nombre: string): string {
  return nombre
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((parte) => parte.charAt(0).toLocaleUpperCase('es-AR'))
    .join('');
}

/**
 * Marco de la app logueada: menú lateral con la ferretería, las secciones y el usuario con su rol, y el
 * contenido. La barra superior la pone cada pantalla con `<app-barra-acciones>`. También es el contenedor de la
 * cotización del menú: la lee al abrir y cada 5 minutos, y se la pasa al bloque presentacional.
 *
 * El menú: «Mostrador» fijo arriba y los grupos de `menu.ts`, que se abren y cierran con un clic; el de la pantalla
 * abierta se abre solo. El shell lleva en `data-seccion` la sección de la pantalla, que le da su tono (bloque 2, S1-S2).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CotizacionLateral, IconoDelMenu, BuscadorMenu],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
  host: { '[attr.data-seccion]': 'seccion()' },
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly cotizaciones = inject(CotizacionService);
  protected readonly rolLabels = ROL_LABELS;
  protected readonly mostrador = MOSTRADOR;

  /** Lo último que se leyó; si un pedido falla se conserva, y null hasta la primera respuesta. */
  protected readonly cotizacion = signal<Cotizacion | null>(null);

  protected readonly grupos = computed(() => gruposPara(this.auth.esDueno()));
  private readonly url = signal(this.router.url);
  protected readonly seccion = computed(() => seccionDe(this.url()));
  private readonly abiertos = signal<ReadonlySet<Seccion>>(new Set());

  constructor() {
    this.abrirElDe(this.router.url);
    this.router.events
      .pipe(
        filter((evento): evento is NavigationEnd => evento instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((evento) => this.abrirElDe(evento.urlAfterRedirects));

    // Se lee al abrir, cada 5 minutos y cuando la configuración o un recálculo avisan que la cotización cambió.
    // El super admin no tiene ferretería: la cotización es un dato de ferretería y no se le pide. Con la contraseña
    // por cambiar tampoco (el servidor responde 403, hallazgo 5): se lee en cuanto el usuario la cambia.
    const contrasenaCambiada$ = toObservable(this.auth.debeCambiarContrasena).pipe(
      pairwise(),
      filter(([antes, ahora]) => antes && !ahora),
    );
    merge(interval(INTERVALO_COTIZACION_MS).pipe(startWith(0)), this.cotizaciones.refrescos$, contrasenaCambiada$)
      .pipe(
        filter(() => !this.auth.esSuperAdmin() && !this.auth.debeCambiarContrasena()),
        // Un pedido nuevo deja sin efecto a uno anterior que siga en camino.
        switchMap(() => this.cotizaciones.obtener().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((cotizacion) => this.cotizacion.set(cotizacion));
  }

  protected readonly iniciales = computed(() => iniciales(this.auth.sesion()?.nombre ?? ''));

  protected abierto(seccion: Seccion): boolean {
    return this.abiertos().has(seccion);
  }

  protected alternar(seccion: Seccion): void {
    this.abiertos.update((abiertos) => {
      const nuevos = new Set(abiertos);
      if (!nuevos.delete(seccion)) nuevos.add(seccion);
      return nuevos;
    });
  }

  /** Al llegar a una pantalla se abre el grupo de su sección; los que el usuario abrió quedan abiertos. */
  private abrirElDe(url: string): void {
    this.url.set(url);
    const seccion = seccionDe(url);
    if (seccion && !this.abierto(seccion)) this.alternar(seccion);
  }

  /** Lo resuelve el enlace y no el navegador: un `#contenido` en la URL lo tomaría el router. */
  protected saltarAlContenido(evento: Event, contenido: HTMLElement): void {
    evento.preventDefault();
    contenido.focus();
  }

  protected salir(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
