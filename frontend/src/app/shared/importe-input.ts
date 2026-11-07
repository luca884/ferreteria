import { DestroyRef, Directive, ElementRef, booleanAttribute, forwardRef, inject, input } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import {
  centavosDeImporte,
  normalizarImporte,
  posicionTrasNormalizar,
  textoDeImporte,
  textoDePlata,
} from '../core/formato/importe';
import { CampoMoneda } from './campo-moneda';

/**
 * Campo de importe (T14 de hallazgos): se tipean dígitos y, para los centavos, una coma; los puntos de miles aparecen
 * solos mientras se escribe y el punto tecleado no entra. Sirve de dos maneras:
 * - suelto, con `[value]` y `(input)`: el componente recibe el texto ya normalizado («50.000») y lo lee con
 *   `centavosDeImporte`;
 * - con `formControl` o `formControlName`: el modelo es el número en pesos (`50000`) o null si está vacío.
 * Dentro de un `app-campo-moneda` con moneda es un campo de plata: el valor cargado y el que queda al salir del campo
 * se ven con dos decimales («578.378,00»). Mientras se escribe, la máscara es la de siempre.
 */
@Directive({
  selector: 'input[appImporte]',
  host: {
    type: 'text',
    inputmode: 'decimal',
    autocomplete: 'off',
    spellcheck: 'false',
    '(blur)': 'alSalir()',
  },
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => ImporteInput), multi: true }],
})
export class ImporteInput implements ControlValueAccessor {
  /** Cuántos decimales se aceptan después de la coma; 0 = sin coma. */
  readonly decimales = input(2);
  /** Acepta un menos adelante (ajustes que restan). Sin esto, el menos no entra. */
  readonly negativos = input(false, { transform: booleanAttribute });

  private readonly campo = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;
  /** El envoltorio de plata, si lo hay: con moneda, el campo muestra dos decimales. */
  private readonly campoMoneda = inject(CampoMoneda, { optional: true });
  private alCambiar: (valor: number | null) => void = () => {};
  private alTocar: () => void = () => {};

  constructor() {
    // En captura: corre antes que el (input) del componente, así todos leen el texto ya normalizado.
    const normalizar = () => this.normalizar();
    this.campo.addEventListener('input', normalizar, { capture: true });
    inject(DestroyRef).onDestroy(() => this.campo.removeEventListener('input', normalizar, { capture: true }));
  }

  writeValue(valor: number | null | undefined): void {
    this.campo.value = this.esPlata() ? textoDePlata(valor) : textoDeImporte(valor);
  }

  /**
   * Al salir de un campo de plata, el texto se completa con dos decimales («9.500» → «9.500,00»). Solo cambia lo que se
   * ve: el número es el mismo, así que no se avisa ningún cambio (el control no queda modificado por tocarlo).
   */
  protected alSalir(): void {
    if (this.esPlata()) {
      const texto = this.campo.value.trim();
      const negativo = texto.startsWith('-');
      const centavos = centavosDeImporte(negativo ? texto.slice(1) : texto);
      if (centavos !== null) this.campo.value = textoDePlata((negativo ? -centavos : centavos) / 100);
    }
    this.alTocar();
  }

  private esPlata(): boolean {
    return (this.campoMoneda?.moneda() ?? null) !== null;
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
