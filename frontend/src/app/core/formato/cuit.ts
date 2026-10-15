import { AbstractControl, ValidationErrors } from '@angular/forms';

const PESOS = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
const MENSAJE_FORMATO = 'El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.';
const MENSAJE_VERIFICADOR = 'El CUIT no es válido: revisá los números, el último no coincide.';

/**
 * CUIT escrito con o sin guiones (también espacios o puntos): lo devuelve como 30-12345678-1 si el dígito
 * verificador cierra, `null` si está vacío, o el mensaje de error. Misma cuenta que Cuit.java: 11 menos el
 * resto de dividir por 11 la suma ponderada (5,4,3,2,7,6,5,4,3,2); 11 es 0 y 10 no existe.
 */
export function normalizarCuit(escrito: string): { cuit: string | null } | { error: string } {
  if (escrito.trim() === '') return { cuit: null };
  const digitos = escrito.replace(/[\s.-]/g, '');
  if (!/^\d{11}$/.test(digitos)) return { error: MENSAJE_FORMATO };
  const suma = PESOS.reduce((total, peso, i) => total + Number(digitos[i]) * peso, 0);
  let verificador = 11 - (suma % 11);
  if (verificador === 11) verificador = 0;
  if (verificador === 10 || verificador !== Number(digitos[10])) return { error: MENSAJE_VERIFICADOR };
  return { cuit: `${digitos.slice(0, 2)}-${digitos.slice(2, 10)}-${digitos[10]}` };
}

/** Validador de formulario: `{ cuit: mensaje }` si el CUIT escrito no es válido; vacío es válido. */
export function validarCuit(control: AbstractControl<string | null>): ValidationErrors | null {
  const resultado = normalizarCuit(control.value ?? '');
  return 'error' in resultado ? { cuit: resultado.error } : null;
}
