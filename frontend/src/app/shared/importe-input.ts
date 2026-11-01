import { DestroyRef, Directive, ElementRef, booleanAttribute, forwardRef, inject, input } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { centavosDeImporte, normalizarImporte, posicionTrasNormalizar, textoDeImporte } from '../core/formato/importe';

/**
 * Campo de importe (T14 de hallazgos): se tipean dígitos y, para los centavos, una coma; los puntos de miles aparecen
 * solos mientras se escribe y el punto tecleado no entra. Sirve de dos maneras:
 * - suelto, con `[value]` y `(input)`: el componente recibe el texto ya normalizado («50.000») y lo lee con
 *   `centavosDeImporte`;
 * - con `formControl` o `formControlName`: el modelo es el número en pesos (`50000`) o null si está vacío.
 */
@Directive({
  selector: 'input[appImporte]',
  host: {
    type: 'text',
    inputmode: 'decimal',
    autocomplete: 'off',
    spellcheck: 'false',
    '(blur)': 'alTocar()',
  },
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => ImporteInput), multi: true }],
})
export class ImporteInput implements ControlValueAccessor {
  /** Cuántos decimales se aceptan después de la coma; 0 = sin coma. */
  readonly decimales = input(2);
  /** Acepta un menos adelante (ajustes que restan). Sin esto, el menos no entra. */
  readonly negativos = input(false, { transform: booleanAttribute });

  private readonly campo = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;
  private alCambiar: (valor: number | null) => void = () => {};
  protected alTocar: () => void = () => {};

  constructor() {
    // En captura: corre antes que el (input) del componente, así todos leen el texto ya normalizado.
    const normalizar = () => this.normalizar();
    this.campo.addEventListener('input', normalizar, { capture: true });
    inject(DestroyRef).onDestroy(() => this.campo.removeEventListener('input', normalizar, { capture: true }));
  }

  writeValue(valor: number | null | undefined): void {
    this.campo.value = textoDeImporte(valor);
  }

  registerOnChange(fn: (valor: number | null) => void): void {
    this.alCambiar = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.alTocar = fn;
  }

  setDisabledState(deshabilitado: boolean): void {
    this.campo.disabled = deshabilitado;
  }

  private normalizar(): void {
    const escrito = this.campo.value;
    const normalizado = normalizarImporte(escrito, this.decimales(), this.negativos());
    if (normalizado !== escrito) {
      const cursor = posicionTrasNormalizar(escrito, this.campo.selectionStart ?? escrito.length, normalizado);
      this.campo.value = normalizado;
      this.campo.setSelectionRange(cursor, cursor);
    }
    const negativo = normalizado.startsWith('-');
    const centavos = centavosDeImporte(negativo ? normalizado.slice(1) : normalizado);
    this.alCambiar(centavos === null ? null : (negativo ? -centavos : centavos) / 100);
  }
}
