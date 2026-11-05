import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal, viewChild } from '@angular/core';
import { erroresDeCampos, erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ContrasenaForm, NuevaContrasena } from '../../shared/contrasena-form';

/**
 * Cambiar mi contraseña (todo usuario logueado). Pide la actual. Si la actual es incorrecta el servidor responde 400 con
 * la clave `passwordActual` (nunca 401, que desloguearía): el error se muestra en ese campo y la sesión sigue abierta.
 * Contenedor: habla con AuthService.
 */
@Component({
  selector: 'app-mi-contrasena',
  imports: [BarraAcciones, ContrasenaForm],
  templateUrl: './mi-contrasena.html',
  styleUrl: './mi-contrasena.css',
})
export class MiContrasena {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly formulario = viewChild.required(ContrasenaForm);

  /** La contraseña la eligió otra persona: se pide cambiarla antes de seguir y al cambiarla se va al inicio. */
  protected readonly debeCambiar = this.auth.debeCambiarContrasena;
  protected readonly aviso = signal<string | null>(null);
  protected readonly errorActual = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly enviando = signal(false);

  protected cambiar(datos: NuevaContrasena): void {
    this.aviso.set(null);
    this.errorActual.set(null);
    this.error.set(null);
    this.enviando.set(true);
    const eraObligatorio = this.debeCambiar();
    this.auth.cambiarContrasena(datos.actual ?? '', datos.nueva).subscribe({
      next: () => {
        this.enviando.set(false);
        this.formulario().reiniciar();
        if (eraObligatorio) {
          // La raíz redirige con inicioPorRol: cada rol cae en su pantalla de siempre.
          void this.router.navigateByUrl('/');
          return;
        }
        this.aviso.set('Contraseña actualizada.');
      },
      error: (err: unknown) => {
        this.enviando.set(false);
        const actual = erroresPorCampo(err)['passwordActual'];
        if (actual !== undefined) this.errorActual.set(actual);
        else {
          const porCampo = erroresDeCampos(err);
          // 429: demasiadas actuales incorrectas seguidas; el servidor dice cuántos minutos faltan.
          const otro =
            err instanceof HttpErrorResponse && err.status === 429
              ? 'Demasiados intentos. Probá de nuevo en unos minutos.'
              : 'No se pudo cambiar la contraseña.';
          this.error.set(porCampo.length > 0 ? porCampo.join(' ') : mensajeDe(err, otro));
        }
      },
    });
  }
}
