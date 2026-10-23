/**
 * Códigos de barras de las etiquetas (GDS-31): EAN-13 y Code128, como módulos ('1' barra, '0' espacio) para dibujar
 * en SVG. Sin librerías: las tablas son las de los estándares.
 */

export type Simbologia = 'EAN-13' | 'Code128';

export interface CodigoDeBarras {
  simbologia: Simbologia;
  /** Lo que va escrito debajo de las barras. */
  texto: string;
  modulos: string;
}

export interface Barra {
  x: number;
  ancho: number;
}

// ---- EAN-13 ----

/** Juego L de cada dígito; el R es su complemento y el G es el R al revés. */
const EAN_L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
/** Paridad (L o G) de los seis dígitos de la izquierda según el primer dígito, que no se dibuja. */
const EAN_PARIDAD = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

const complemento = (m: string) => m.replace(/./g, (c) => (c === '1' ? '0' : '1'));
const eanR = (d: number) => complemento(EAN_L[d]);
const eanG = (d: number) => eanR(d).split('').reverse().join('');

/** 13 dígitos con el verificador correcto (pesos 1 y 3 alternados desde la izquierda). */
export function esEan13(codigo: string): boolean {
  if (!/^\d{13}$/.test(codigo)) return false;
  const digitos = codigo.split('').map(Number);
  const suma = digitos.slice(0, 12).reduce((total, d, i) => total + d * (i % 2 === 0 ? 1 : 3), 0);
  return (10 - (suma % 10)) % 10 === digitos[12];
}

/** Los 95 módulos de un EAN-13 válido (ver {@link esEan13}). */
export function ean13(codigo: string): string {
  const d = codigo.split('').map(Number);
  const paridad = EAN_PARIDAD[d[0]];
  const izquierda = d.slice(1, 7).map((n, i) => (paridad[i] === 'L' ? EAN_L[n] : eanG(n))).join('');
  const derecha = d.slice(7).map(eanR).join('');
  return `101${izquierda}01010${derecha}101`;
}

// ---- Code128 ----

/** Anchos (barra, espacio, ...) de los valores 0 a 105; 103-105 son los inicios A, B y C. */
const CODE128 = (
  '212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 122132 122231 113222 ' +
  '123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 321221 312212 322112 322211 212123 212321 ' +
  '232121 111323 131123 131321 112313 132113 132311 211313 231113 231311 112133 112331 132131 113123 113321 133121 ' +
  '313121 211331 231131 213113 213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 ' +
  '111422 121124 121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 ' +
  '111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 412121 111143 111341 131141 114113 ' +
  '114311 411113 411311 113141 114131 311141 411131 211412 211214 211232'
).split(' ');
const PARADA = '2331112';
const INICIO_B = 104;
const INICIO_C = 105;
const A_B = 100;
const A_C = 99;

const esDigito = (c: string | undefined) => c !== undefined && c >= '0' && c <= '9';

function digitosDesde(texto: string, i: number): number {
  let n = 0;
  while (esDigito(texto[i + n])) n++;
  return n;
}

function anchosAModulos(anchos: string): string {
  return anchos
    .split('')
    .map((w, i) => (i % 2 === 0 ? '1' : '0').repeat(Number(w)))
    .join('');
}

/**
 * Code128 con los juegos B (ASCII imprimible) y C (pares de dígitos): las tiradas de 4 dígitos o más van en C, que es
 * más corto. Null si está vacío o tiene un carácter fuera del ASCII imprimible (una Ñ, por ejemplo).
 */
export function code128(texto: string): string | null {
  if (texto === '' || [...texto].some((c) => c < ' ' || c > '~')) return null;

  const inicial = digitosDesde(texto, 0);
  const enC = inicial >= 4 || (inicial === texto.length && inicial % 2 === 0);
  const valores = [enC ? INICIO_C : INICIO_B];
  let juego: 'B' | 'C' = enC ? 'C' : 'B';
  let i = 0;
  while (i < texto.length) {
    if (juego === 'C') {
      if (esDigito(texto[i]) && esDigito(texto[i + 1])) {
        valores.push(Number(texto.slice(i, i + 2)));
        i += 2;
        continue;
      }
      valores.push(A_B);
      juego = 'B';
    }
    const tirada = digitosDesde(texto, i);
    if (tirada >= 4) {
      // Con cantidad impar, el primer dígito va en B y el resto en pares.
      if (tirada % 2 === 1) valores.push(texto.charCodeAt(i++) - 32);
      valores.push(A_C);
      juego = 'C';
      continue;
    }
    valores.push(texto.charCodeAt(i++) - 32);
  }
  const verificador = valores.reduce((suma, v, pos) => suma + v * Math.max(pos, 1), 0) % 103;
  return [...valores, verificador].map((v) => anchosAModulos(CODE128[v])).join('') + anchosAModulos(PARADA);
}

// ---- etiqueta ----

/**
 * El código de la etiqueta de un artículo: su código de barras en EAN-13 si es uno válido, si no en Code128; sin código
 * de barras (o si no se puede codificar), el código interno en Code128. Null si tampoco se puede.
 */
export function codigoParaEtiqueta(articulo: {
  codigoBarras: string | null;
  codigoInterno: string;
}): CodigoDeBarras | null {
  const deBarras = articulo.codigoBarras?.trim() ?? '';
  if (esEan13(deBarras)) return { simbologia: 'EAN-13', texto: deBarras, modulos: ean13(deBarras) };
  for (const texto of [deBarras, articulo.codigoInterno.trim()]) {
    const modulos = code128(texto);
    if (modulos) return { simbologia: 'Code128', texto, modulos };
  }
  return null;
}

/** Las barras (tiradas de '1') con su posición y ancho en módulos, para los `<rect>` del SVG. */
export function barras(modulos: string): Barra[] {
  const lista: Barra[] = [];
  for (let x = 0; x < modulos.length; x++) {
    if (modulos[x] !== '1') continue;
    let ancho = 1;
    while (modulos[x + ancho] === '1') ancho++;
    lista.push({ x, ancho });
    x += ancho;
  }
  return lista;
}
