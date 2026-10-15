import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { esRutaPropia } from '../../core/auth/ruta-propia';

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

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * La URL que pidió antes de loguearse (returnUrl), solo si es una ruta propia: empieza con una sola «/» y
   * no es el login. Si no, la raíz, que reparte por rol.
   */
  private destino(): string {
    const pedida = this.route.snapshot.queryParamMap.get('returnUrl');
    return esRutaPropia(pedida) ? pedida : '/';
  }

  protected ingresar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => void this.router.navigateByUrl(this.destino()),
      error: (err: unknown) => {
        this.enviando.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 401
            ? 'Email o contraseña incorrectos.'
            : 'No pudimos conectar con el servidor. Probá de nuevo.',
        );
      },
    });
  }
}
