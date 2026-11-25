import { DIAS_VALIDEZ_MAXIMOS } from '../presupuestos/presupuesto.models';

export const MENSAJE_VALIDEZ = `La validez va de 1 a ${DIAS_VALIDEZ_MAXIMOS} días.`;

/** Los días de validez que escribió la persona, o null si no son un entero de 1 al máximo. Lo usan el diálogo y el panel. */
export function diasDeValidez(texto: string): number | null {
  if (!/^\s*\d+\s*$/.test(texto)) return null;
  const dias = Number(texto);
  return dias >= 1 && dias <= DIAS_VALIDEZ_MAXIMOS ? dias : null;
}
