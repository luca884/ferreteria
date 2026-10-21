import { Component, inject, input, output } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';

export const CONTRASENA_MIN = 8;
export const CONTRASENA_MAX = 72;

/** Lo que emite el formulario: la actual solo si se la pidió. */
export interface NuevaContrasena {
  actual?: string;
  nueva: string;
}

export const CONTRASENA_SOLO_ESPACIOS = 'La contraseña no puede ser solo espacios.';

/** Una contraseña de solo espacios no es válida (el servidor tampoco la acepta). */
export const noSoloEspacios = (control: AbstractControl): ValidationErrors | null =>
  typeof control.value === 'string' && control.value.length > 0 && control.value.trim() === ''
    ? { soloEspacios: true }
    : null;

const coincide = (grupo: AbstractControl): ValidationErrors | null =>
  grupo.get('nueva')?.value === grupo.get('confirmacion')?.value ? null : { noCoincide: true };

/**
 * Formulario de contraseña nueva (con la actual si `pedirActual`), compartido por «Cambiar mi contraseña» y por los
 * resets del Dueño y del super admin. Presentacional: valida el largo (8 a 72) y la confirmación sin llamar al API, y
 * emite `enviar`; el contenedor hace la llamada y devuelve los errores del servidor por `errorServidor` y `errorActual`.
 */
@Component({
  selector: 'app-contrasena-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="enviarFormulario()" novalidate>
      @if (errorServidor(); as mensaje) {
        <p class="error" role="alert">{{ mensaje }}</p>
      }
      @if (pedirActual()) {
        <div class="campo">
          <label for="actual">Contraseña actual</label>
          <input
            id="actual"
            name="actual"
            type="password"
            autocomplete="current-password"
            formControlName="actual"
            [attr.aria-invalid]="actualInvalida() ? 'true' : null"
            [attr.aria-describedby]="actualInvalida() ? 'actual-error' : null"
          />
          @if (errorActual(); as mensaje) {
            <span id="actual-error" class="campo-error">{{ mensaje }}</span>
          } @else if (form.controls.actual.touched && form.controls.actual.invalid) {
            <span id="actual-error" class="campo-error">Ingresá tu contraseña actual.</span>
          }
        </div>
      }
      <div class="campo">
        <label for="nueva">Contraseña nueva</label>
        <input
          id="nueva"
          name="nueva"
          type="password"
          autocomplete="new-password"
          formControlName="nueva"
          [attr.aria-invalid]="nuevaInvalida() ? 'true' : null"
          [attr.aria-describedby]="nuevaInvalida() ? 'nueva-error' : 'nueva-pista'"
        />
        @if (nuevaInvalida()) {
          <span id="nueva-error" class="campo-error">
            @if (form.controls.nueva.hasError('soloEspacios')) {
              {{ soloEspacios }}
            } @else {
              La contraseña tiene que tener entre {{ min }} y {{ max }} caracteres.
            }
          </span>
        } @else {
          <span id="nueva-pista" class="pista">Entre {{ min }} y {{ max }} caracteres.</span>
        }
      </div>
      <div class="campo">
        <label for="confirmacion">Repetí la contraseña nueva</label>
        <input
          id="confirmacion"
          name="confirmacion"
          type="password"
          autocomplete="new-password"
          formControlName="confirmacion"
          [attr.aria-invalid]="confirmacionInvalida() ? 'true' : null"
          [attr.aria-describedby]="confirmacionInvalida() ? 'confirmacion-error' : null"
        />
        @if (confirmacionInvalida()) {
          <span id="confirmacion-error" class="campo-error">Las contraseñas no coinciden.</span>
        }
      </div>
      <div class="acciones-alta">
        <button type="submit" [disabled]="enviando()">{{ textoEnviar() }}</button>
      </div>
    </form>
  `,
})
export class ContrasenaForm {
  readonly pedirActual = input(false);
  /** Error general que devolvió el servidor (422, 403, caída de red…). */
  readonly errorServidor = input<string | null>(null);
  /** Error de la contraseña actual (400 con la clave `passwordActual`): va junto a su campo. */
  readonly errorActual = input<string | null>(null);
  readonly enviando = input(false);
  readonly textoEnviar = input('Guardar contraseña');

  readonly enviar = output<NuevaContrasena>();

  protected readonly min = CONTRASENA_MIN;
  protected readonly max = CONTRASENA_MAX;
  protected readonly soloEspacios = CONTRASENA_SOLO_ESPACIOS;

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      actual: [''],
      nueva: ['', [Validators.required, Validators.minLength(CONTRASENA_MIN), Validators.maxLength(CONTRASENA_MAX), noSoloEspacios]],
      confirmacion: [''],
    },
    { validators: coincide },
  );

  protected actualInvalida(): boolean {
    const actual = this.form.controls.actual;
    return this.errorActual() !== null || (actual.touched && actual.invalid);
  }

  protected nuevaInvalida(): boolean {
    const nueva = this.form.controls.nueva;
    return nueva.touched && nueva.invalid;
  }

  protected confirmacionInvalida(): boolean {
    return this.form.controls.confirmacion.touched && this.form.hasError('noCoincide');
  }

  protected enviarFormulario(): void {
    const actual = this.form.controls.actual;
    if (this.pedirActual()) actual.setValidators(Validators.required);
    else actual.clearValidators();
    actual.updateValueAndValidity({ emitEvent: false });

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { actual: valorActual, nueva } = this.form.getRawValue();
    this.enviar.emit(this.pedirActual() ? { actual: valorActual, nueva } : { nueva });
  }

  /** Vacía los campos y saca los errores (después de un cambio exitoso). */
  reiniciar(): void {
    this.form.reset({ actual: '', nueva: '', confirmacion: '' });
  }
}
