/**
 * Importes escritos a mano (T14 de hallazgos): solo se tipean dígitos y, para los centavos, una coma; los puntos de
 * miles los pone la máscara. El punto nunca se escribe, así «50.000» no se puede leer como cincuenta pesos.
 */

/**
 * El texto como se ve: dígitos agrupados con punto de miles y, si se escribió la coma, hasta `decimales` decimales.
 * Con `negativos`, un menos adelante se conserva (los ajustes de precio en pesos pueden restar).
 */
export function normalizarImporte(texto: string, decimales = 2, negativos = false): string {
  const signo = negativos && texto.trimStart().startsWith('-') ? '-' : '';
  let entera = '';
  let fraccion: string | null = null;
  for (const letra of texto) {
    if (letra >= '0' && letra <= '9') {
      if (fraccion === null) entera += letra;
      else if (fraccion.length < decimales) fraccion += letra;
    } else if (letra === ',' && fraccion === null && decimales > 0) {
      fraccion = '';
    }
  }
  entera = entera.replace(/^0+(?=\d)/, '');
  if (fraccion !== null && entera === '') entera = '0';
  return signo + agruparMiles(entera) + (fraccion === null ? '' : `,${fraccion}`);
}

/**
 * Un importe escrito, en centavos: los puntos son de miles y la coma separa hasta 2 decimales. Null si está vacío o no
 * es un importe (negativo, con letras o con más de 2 decimales).
 */
export function centavosDeImporte(texto: string): number | null {
  const m = /^(\d*)(?:,(\d{0,2}))?$/.exec(texto.trim().replaceAll('.', ''));
  if (!m || (m[1] === '' && !m[2])) return null;
  const centavos = Number((m[1] || '0') + (m[2] ?? '').padEnd(2, '0'));
  return Number.isSafeInteger(centavos) ? centavos : null;
}

/** Un importe en pesos como se escribe en el campo: «50.000» si es redondo, «1.234,50» si tiene centavos. */
export function textoDeImporte(valor: number | null | undefined): string {
  if (valor === null || valor === undefined) return '';
  const centavos = Math.round(Math.abs(valor) * 100);
  const signo = valor < 0 && centavos > 0 ? '-' : '';
  const entera = signo + agruparMiles(String(Math.trunc(centavos / 100)));
  return centavos % 100 === 0 ? entera : `${entera},${String(centavos % 100).padStart(2, '0')}`;
}

/**
 * Dónde queda el cursor después de normalizar: detrás de la misma cantidad de dígitos y comas que tenía antes, así
 * los puntos que aparecen o desaparecen no lo corren.
 */
export function posicionTrasNormalizar(escrito: string, cursor: number, normalizado: string): number {
  const antes = (escrito.slice(0, cursor).match(/[\d,-]/g) ?? []).length;
  if (antes === 0) return 0;
  let vistos = 0;
  for (let i = 0; i < normalizado.length; i++) {
    if (/[\d,-]/.test(normalizado[i]) && ++vistos === antes) return i + 1;
  }
  return normalizado.length;
}

function agruparMiles(entera: string): string {
  return entera.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}
