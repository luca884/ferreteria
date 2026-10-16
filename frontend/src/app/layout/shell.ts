import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { EMPTY, catchError, interval, startWith, switchMap } from 'rxjs';
import { ROL_LABELS } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../core/cotizacion/cotizacion.service';
import { CotizacionLateral } from './cotizacion-lateral';

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
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CotizacionLateral],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly cotizaciones = inject(CotizacionService);
  protected readonly rolLabels = ROL_LABELS;

  /** Lo último que se leyó; si un pedido falla se conserva, y null hasta la primera respuesta. */
  protected readonly cotizacion = signal<Cotizacion | null>(null);

  constructor() {
    interval(INTERVALO_COTIZACION_MS)
      .pipe(
        startWith(0),
        // Un pedido nuevo deja sin efecto a uno anterior que siga en camino.
        switchMap(() => this.cotizaciones.obtener().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((cotizacion) => this.cotizacion.set(cotizacion));
  }

  protected readonly iniciales = computed(() => iniciales(this.auth.sesion()?.nombre ?? ''));

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
