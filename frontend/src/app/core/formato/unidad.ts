/** Unidad de venta de un artículo (la misma lista que el backend). */
export type UnidadDeMedida = 'UNIDAD' | 'METRO' | 'KILO' | 'LITRO';

const NOMBRES: Record<UnidadDeMedida, [singular: string, plural: string]> = {
  UNIDAD: ['unidad', 'unidades'],
  METRO: ['metro', 'metros'],
  KILO: ['kilo', 'kilos'],
  LITRO: ['litro', 'litros'],
};

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

/**
 * Cantidad con su unidad en castellano: «1 unidad», «497 unidades», «145,5 metros», «2 kilos». Singular solo
 * para 1 (o -1) exacto; hasta 3 decimales, con coma, y miles con punto.
 */
export function cantidadConUnidad(cantidad: number, unidad: UnidadDeMedida): string {
  const texto = NUMERO.format(cantidad);
  const [singular, plural] = NOMBRES[unidad];
  return `${texto} ${texto === '1' || texto === '-1' ? singular : plural}`;
}

const ABREVIATURAS: Record<UnidadDeMedida, string> = { UNIDAD: '', METRO: 'm', KILO: 'kg', LITRO: 'l' };

/** Lo que va después de un precio para decir por qué unidad es: « / kg», « / m», « / l». Por pieza no lleva (#36). */
export function porUnidad(unidad: UnidadDeMedida): string {
  const abreviatura = ABREVIATURAS[unidad];
  return abreviatura ? ` / ${abreviatura}` : '';
}
