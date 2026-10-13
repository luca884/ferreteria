/**
 * Un lector de código de barras escribe como un teclado, pero mucho más rápido que una persona:
 * entre 5 y 20 ms por carácter, parejo. Una persona tipea a 80-200 ms por tecla.
 *
 * - 30 ms de tope entre teclas: deja margen a lectores lentos (USB económicos, Bluetooth) y
 *   sigue lejos del tipeo humano, incluso de un mecanógrafo rápido.
 * - Mínimo 4 caracteres: dos teclas apretadas casi juntas (rebote, doble toque) pasan los 30 ms,
 *   pero ningún humano encadena 4 seguidas así. Los códigos de barras tienen 8 o más.
 */
export const GAP_MAXIMO_RAFAGA_MS = 30;
export const LARGO_MINIMO_RAFAGA = 4;

/**
 * Distingue un escaneo de un tipeo por la velocidad de las teclas. Es puro: recibe los instantes
 * (en ms) en vez de leer el reloj, así se prueba sin timers.
 *
 * Una ráfaga son caracteres con menos de `GAP_MAXIMO_RAFAGA_MS` entre uno y el siguiente. Si
 * termina en Enter (también a menos de ese tope de la última tecla) y tiene al menos
 * `LARGO_MINIMO_RAFAGA` caracteres, es un escaneo y `alEnter` devuelve solo ese contenido.
 */
export class DetectorLector {
  private rafaga = '';
  private ultimaTecla: number | null = null;

  /** Registra un carácter tipeado en el instante `ahora`. */
  tecla(caracter: string, ahora: number): void {
    const sigue = this.ultimaTecla !== null && ahora - this.ultimaTecla < GAP_MAXIMO_RAFAGA_MS;
    this.rafaga = sigue ? this.rafaga + caracter : caracter;
    this.ultimaTecla = ahora;
  }

  /** Registra el Enter. Devuelve el contenido de la ráfaga si fue un escaneo, o `null` si no. */
  alEnter(ahora: number): string | null {
    const esRafaga =
      this.ultimaTecla !== null &&
      ahora - this.ultimaTecla < GAP_MAXIMO_RAFAGA_MS &&
      this.rafaga.length >= LARGO_MINIMO_RAFAGA;
    const contenido = esRafaga ? this.rafaga : null;
    this.reiniciar();
    return contenido;
  }

  /** Descarta la ráfaga en curso: una tecla que edita el texto (Backspace, flechas) la corta. */
  reiniciar(): void {
    this.rafaga = '';
    this.ultimaTecla = null;
  }
}
