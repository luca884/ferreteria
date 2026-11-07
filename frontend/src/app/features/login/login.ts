import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { esRutaPropia } from '../../core/auth/ruta-propia';

/** El `message` genérico del 401 del backend (CredencialesInvalidasException). */
const GENERICO_DEL_SERVIDOR = 'Email o contraseña incorrectos';

const SIN_CONEXION = 'No pudimos conectar con el servidor. Probá de nuevo.';

/** Los campos en el orden de la pantalla: al enviar con errores se enfoca el primero que falla. */
const CAMPOS = ['email', 'password'] as const;
type Campo = (typeof CAMPOS)[number];

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  /**
   * El servidor cerró la sesión abierta (ferretería suspendida, usuario dado de baja, rol cambiado o vencida): el
   * interceptor manda acá con `sesion=cerrada` (bugs de la guía, S15). El motivo, si es la suspensión, lo dice el
   * login al volver a ingresar.
   */
  protected readonly sesionCerrada = this.route.snapshot.queryParamMap.get('sesion') === 'cerrada';

  /**
   * La URL que pidió antes de loguearse (returnUrl), solo si es una ruta propia: empieza con una sola «/» y
   * no es el login. Si no, la raíz, que reparte por rol.
   */
  private destino(): string {
    const pedida = this.route.snapshot.queryParamMap.get('returnUrl');
    return esRutaPropia(pedida) ? pedida : '/';
  }

  protected invalido(campo: Campo): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** El error del campo y, si lo hay, el del servidor (vale para los dos: email o contraseña). */
  protected descripcion(campo: Campo): string | null {
    const ids = [this.invalido(campo) ? `${campo}-error` : null, this.error() ? 'login-error' : null];
    return ids.filter(Boolean).join(' ') || null;
  }

  protected ingresar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      const primero = CAMPOS.find((campo) => this.form.controls[campo].invalid);
      if (primero) this.host.querySelector<HTMLElement>(`#${primero}`)?.focus();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => void this.router.navigateByUrl(this.destino()),
      error: (err: unknown) => {
        this.enviando.set(false);
        this.error.set(this.mensajeDeError(err));
      },
    });
  }

  private mensajeDeError(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return SIN_CONEXION;
    if (err.status === 401) return this.mensajeDeCredenciales(err);
    // Límite de intentos: el servidor dice cuántos minutos faltan.
    if (err.status === 429) return mensajeDe(err, 'Demasiados intentos. Probá de nuevo en unos minutos.');
    return SIN_CONEXION;
  }

  /** El servidor explica un 401 solo cuando hay algo que decir (ferretería suspendida); si no, el texto de siempre. */
  private mensajeDeCredenciales(err: HttpErrorResponse): string {
    const mensaje = mensajeDe(err, GENERICO_DEL_SERVIDOR);
    return mensaje === GENERICO_DEL_SERVIDOR ? 'Email o contraseña incorrectos.' : mensaje;
  }
}
