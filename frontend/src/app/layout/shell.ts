import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ROL_LABELS } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';

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
 * contenido. La barra superior la pone cada pantalla con `<app-barra-acciones>`.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly rolLabels = ROL_LABELS;

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
