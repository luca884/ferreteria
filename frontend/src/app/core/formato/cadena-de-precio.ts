import { Alicuota, Decimal, Exacto, PORCENTAJE_IVA, exacto, precioFinal } from './precio-final';

/**
 * La cadena del desglose del precio, calculada en la pantalla con la misma cuenta que CostoDeLista.java y
 * CadenaDePrecio.java: todo es multiplicación exacta con BigInt y UNA sola división final, HALF_UP. Los pasos
 * intermedios van a 4 decimales solo para mostrarse; el costo y el precio, a 2. El precio sale de `precioFinal`, sin
 * otra copia del redondeo. Los casos compartidos con el backend viven en casos-cadena-precio.json.
 */
export type TipoPaso =
  | 'LISTA'
  | 'SIN_IVA'
  | 'BONIFICACION'
  | 'AJUSTE'
  | 'COSTO'
  | 'COTIZACION'
  | 'MARGEN'
  | 'IVA'
  | 'REDONDEO'
  | 'PRECIO_FINAL';

/** Un paso: el importe al que se llega, como texto decimal; `porcentaje` es el del paso, si tiene. */
export interface Paso {
  tipo: TipoPaso;
  porcentaje: string | null;
  importe: string;
}

export interface EntradaCadena {
  /** false: sin lista de proveedor, la cadena arranca en el costo guardado. */
  tieneLista: boolean;
  lista: Decimal;
  incluyeIva: boolean | null | undefined;
  /** La alícuota del artículo: la que se le quita a la lista y la del paso de IVA. */
  alicuota: Alicuota;
  /** Hasta tres; null o ausente es «sin bonificación». Positivo descuenta. */
  bonificaciones: readonly Decimal[] | null | undefined;
  /** Hasta tres; null o ausente es «sin ajuste». Negativo descuenta, positivo recarga. */
  ajustes: readonly Decimal[] | null | undefined;
  /** El costo del que sale el precio (en la moneda del artículo). */
  costoGuardado: Decimal;
  moneda: 'ARS' | 'USD';
  /** Pesos por dólar; solo cuenta para un artículo en dólares. */
  cotizacion: Decimal;
  margen: Decimal;
  multiplo: Decimal;
}

export interface CadenaDePrecio {
  pasos: Paso[];
  /** El costo que sale de la lista con la base y los ajustes dados; null sin lista. */
  costoDeLista: string | null;
  /** Si el costo guardado es igual por valor al de la lista; null sin lista. */
  coincide: boolean | null;
  /** El precio final en pesos; null si no se puede calcular (por ejemplo, dólares sin cotización). */
  precio: string | null;
}

/** Una fracción exacta n/d con d > 0. */
interface Fraccion {
  n: bigint;
  d: bigint;
}

const diez = (k: number) => 10n ** BigInt(k);
const CIEN = 100n;

/** n/d redondeado HALF_UP a k decimales, como entero escalado. */
function dividir({ n, d }: Fraccion, k: number): bigint {
  const abs = n < 0n ? -n : n;
  const q = (2n * abs * diez(k) + d) / (2n * d);
  return n < 0n ? -q : q;
}

/** Un entero escalado como texto decimal con k decimales. */
function texto(q: bigint, k: number): string {
  const negativo = q < 0n;
  const digitos = (negativo ? -q : q).toString().padStart(k + 1, '0');
  const entera = digitos.slice(0, digitos.length - k);
  return `${negativo ? '-' : ''}${entera}${k > 0 ? `.${digitos.slice(digitos.length - k)}` : ''}`;
}

const mostrado = (f: Fraccion) => texto(dividir(f, 4), 4);

const aFraccion = (x: Exacto): Fraccion => ({ n: x.entero, d: diez(x.escala) });
const por = (a: Fraccion, b: Fraccion): Fraccion => ({ n: a.n * b.n, d: a.d * b.d });

/** (100 ± p) / 100 con p decimal exacto: el factor de una bonificación (−), un ajuste, el margen o el IVA (+). */
function factor(p: Exacto, signo: 1n | -1n): Fraccion {
  const base = CIEN * diez(p.escala);
  return { n: base + signo * p.entero, d: base };
}

const esAusente = (v: Decimal) => v === null || v === undefined || v === '';

/** Los porcentajes presentes, en orden. null si alguno viene pero no se puede leer. */
function presentes(
  valores: readonly Decimal[] | null | undefined,
): { texto: string; valor: Exacto }[] | null {
  const salida: { texto: string; valor: Exacto }[] = [];
  for (const v of valores ?? []) {
    if (esAusente(v)) continue;
    const valor = exacto(v);
    if (!valor) return null;
    salida.push({ texto: String(v).trim(), valor });
  }
  return salida;
}

interface BaseDeLista {
  /** LISTA, SIN_IVA, BONIFICACION y AJUSTE, cada uno con el importe al que se llega. */
  pasos: Paso[];
  /** El costo de la lista, sin redondear. */
  costo: Fraccion;
}

/** Los pasos de la lista al costo, o null si algún dato no se puede leer. */
function pasosDeLista(entrada: EntradaCadena): BaseDeLista | null {
  const lista = exacto(entrada.lista);
  const bonificaciones = presentes(entrada.bonificaciones);
  const ajustes = presentes(entrada.ajustes);
  if (!lista || !bonificaciones || !ajustes || lista.entero < 0n) return null;

  const pasos: Paso[] = [];
  let acumulado = aFraccion(lista);
  pasos.push({ tipo: 'LISTA', porcentaje: null, importe: mostrado(acumulado) });
  if (entrada.incluyeIva) {
    const iva = exacto(PORCENTAJE_IVA[entrada.alicuota])!;
    // lista / (1 + iva/100) = lista × 100 / (100 + iva)
    acumulado = por(acumulado, {
      n: CIEN * diez(iva.escala),
      d: CIEN * diez(iva.escala) + iva.entero,
    });
    pasos.push({
      tipo: 'SIN_IVA',
      porcentaje: PORCENTAJE_IVA[entrada.alicuota],
      importe: mostrado(acumulado),
    });
  }
  for (const b of bonificaciones) {
    acumulado = por(acumulado, factor(b.valor, -1n));
    pasos.push({ tipo: 'BONIFICACION', porcentaje: b.texto, importe: mostrado(acumulado) });
  }
  for (const a of ajustes) {
    acumulado = por(acumulado, factor(a.valor, 1n));
    pasos.push({ tipo: 'AJUSTE', porcentaje: a.texto, importe: mostrado(acumulado) });
  }
  return { pasos, costo: acumulado };
}

/**
 * El costo que sale de la lista: quita el IVA si la lista lo incluye, aplica las bonificaciones del proveedor y
 * después los ajustes del artículo, con una sola división final a 2 decimales HALF_UP. `null` si falta la lista o
 * algún dato no se puede leer.
 */
export function costoDeLista(
  lista: Decimal,
  alicuota: Alicuota,
  incluyeIva: boolean | null | undefined,
  bonificaciones: readonly Decimal[] | null | undefined,
  ajustes: readonly Decimal[] | null | undefined,
): string | null {
  const base = pasosDeLista({
    tieneLista: true,
    lista,
    incluyeIva,
    alicuota,
    bonificaciones,
    ajustes,
    costoGuardado: null,
    moneda: 'ARS',
    cotizacion: null,
    margen: null,
    multiplo: null,
  });
  return base ? texto(dividir(base.costo, 2), 2) : null;
}

/** Igualdad por valor de dos decimales exactos. */
function iguales(a: Exacto, b: Exacto): boolean {
  return a.entero * diez(b.escala) === b.entero * diez(a.escala);
}

/**
 * Los pasos de C-204: lista → IVA de la lista → bonificaciones → ajustes → costo → cotización (USD) → margen → IVA →
 * redondeo → precio final. Después del costo todo parte del costo GUARDADO, que puede no ser el de la lista.
 */
export function cadenaDePrecio(entrada: EntradaCadena): CadenaDePrecio {
  const pasos: Paso[] = [];
  let costoLista: string | null = null;
  let coincide: boolean | null = null;

  const costo = exacto(entrada.costoGuardado);
  if (entrada.tieneLista) {
    const base = pasosDeLista(entrada);
    if (base) {
      pasos.push(...base.pasos);
      costoLista = texto(dividir(base.costo, 2), 2);
      coincide = costo ? iguales(exacto(costoLista)!, costo) : null;
    }
  }
  if (!costo) return { pasos, costoDeLista: costoLista, coincide, precio: null };
  pasos.push({ tipo: 'COSTO', porcentaje: null, importe: texto(dividir(aFraccion(costo), 2), 2) });

  const margen = exacto(entrada.margen);
  const cotizacion = exacto(entrada.cotizacion);
  const usd = entrada.moneda === 'USD';
  // Un artículo en dólares sin cotización no se puede pasar a pesos: la cadena termina en el costo.
  if (!margen || (usd && (!cotizacion || cotizacion.entero <= 0n))) {
    return { pasos, costoDeLista: costoLista, coincide, precio: null };
  }

  let acumulado = aFraccion(costo);
  if (usd) {
    acumulado = por(acumulado, aFraccion(cotizacion!));
    pasos.push({ tipo: 'COTIZACION', porcentaje: null, importe: mostrado(acumulado) });
  }
  acumulado = por(acumulado, factor(margen, 1n));
  pasos.push({
    tipo: 'MARGEN',
    porcentaje: String(entrada.margen).trim(),
    importe: mostrado(acumulado),
  });
  const iva = exacto(PORCENTAJE_IVA[entrada.alicuota])!;
  acumulado = por(acumulado, factor(iva, 1n));
  pasos.push({
    tipo: 'IVA',
    porcentaje: PORCENTAJE_IVA[entrada.alicuota],
    importe: mostrado(acumulado),
  });

  const multiplo = exacto(entrada.multiplo);
  if (multiplo && multiplo.entero > 0n) {
    pasos.push({ tipo: 'REDONDEO', porcentaje: null, importe: String(entrada.multiplo).trim() });
  }

  const precio = precioFinal(
    entrada.costoGuardado,
    entrada.margen,
    entrada.alicuota,
    entrada.multiplo,
    usd ? entrada.cotizacion : undefined,
  );
  if (precio !== null) {
    pasos.push({ tipo: 'PRECIO_FINAL', porcentaje: null, importe: precio.toFixed(2) });
  }
  return {
    pasos,
    costoDeLista: costoLista,
    coincide,
    precio: precio === null ? null : precio.toFixed(2),
  };
}
