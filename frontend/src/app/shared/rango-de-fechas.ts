/** El texto único de «Hasta» anterior a «Desde», el mismo que da el servidor (bugs-relevamiento-celular, S4). */
export const HASTA_ANTERIOR_A_DESDE = 'La fecha «Hasta» no puede ser anterior a «Desde».';

/**
 * Si las dos fechas (`aaaa-mm-dd`) están y «Hasta» es anterior a «Desde». Con una sola o ninguna no hay nada que
 * validar. Las fechas ISO se comparan como texto.
 */
export function rangoAlReves(desde: string, hasta: string): boolean {
  return desde !== '' && hasta !== '' && hasta < desde;
}
