import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ROL_LABELS } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';

/** Marco de la app logueada: barra con la ferretería, las secciones y el usuario con su rol, y el contenido. */
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

  protected salir(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
