import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal, viewChild } from '@angular/core';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
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
  private readonly formulario = viewChild.required(ContrasenaForm);

  protected readonly aviso = signal<string | null>(null);
  protected readonly errorActual = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly enviando = signal(false);

  protected cambiar(datos: NuevaContrasena): void {
    this.aviso.set(null);
    this.errorActual.set(null);
    this.error.set(null);
    this.enviando.set(true);
    this.auth.cambiarContrasena(datos.actual ?? '', datos.nueva).subscribe({
      next: () => {
        this.enviando.set(false);
        this.formulario().reiniciar();
        this.aviso.set('Contraseña actualizada.');
      },
      error: (err: unknown) => {
        this.enviando.set(false);
        const errors: unknown = err instanceof HttpErrorResponse ? err.error?.errors : undefined;
        const actual = (errors as Record<string, unknown> | null | undefined)?.['passwordActual'];
        if (typeof actual === 'string' && actual.trim() !== '') this.errorActual.set(actual);
        else {
          const porCampo = erroresDeCampos(err);
          this.error.set(porCampo.length > 0 ? porCampo.join(' ') : mensajeDe(err, 'No se pudo cambiar la contraseña.'));
        }
      },
    });
  }
}
