import { formatearCosto } from '../../core/formato/monto';
import { ALICUOTA_IVA_LABELS, UNIDAD_LABELS } from '../articulos/articulo.models';
import { Ajustes, CambioPlaneado } from './importacion.models';
import { FilaDeVista, TipoFila } from './seleccion-filas';

const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

const ETIQUETAS: Record<TipoFila, string> = {
  suben: 'Sube',
  bajan: 'Baja',
  cambianMoneda: 'Cambia de moneda',
  corrigen: 'Corrige IVA o ajustes',
  nuevos: 'Nuevo',
  sinCambio: 'Sin cambio',
  conError: 'Con error',
};

export function etiquetaDeTipo(tipo: TipoFila): string {
  return ETIQUETAS[tipo];
}

/** «descuento 5 %» o «recargo 3 %»: el signo del número, dicho con palabras. */
export function textoAjuste(valor: number): string {
  if (valor === 0) return 'sin ajuste (0 %)';
  return `${valor < 0 ? 'descuento' : 'recargo'} ${PORCENTAJE.format(Math.abs(valor))} %`;
}

/** Los descuentos y recargos que tienen valor, en orden: «descuento 5 % y recargo 3 %»; sin ninguno, «ninguno». */
export function textoAjustes(ajustes: Ajustes): string {
  const textos = [ajustes.a1, ajustes.a2, ajustes.a3]
    .filter((a): a is number => a !== null && a !== undefined)
    .map(textoAjuste);
  if (textos.length === 0) return 'ninguno';
  if (textos.length === 1) return textos[0];
  return `${textos.slice(0, -1).join(', ')} y ${textos[textos.length - 1]}`;
}

function igualesLosAjustes(a: Ajustes, b: Ajustes): boolean {
  return a.a1 === b.a1 && a.a2 === b.a2 && a.a3 === b.a3;
}

function detalleDeCambio(cambio: CambioPlaneado): string[] {
  const detalle: string[] = [];
  if (cambio.monedaAnterior && cambio.monedaNueva && cambio.monedaAnterior !== cambio.monedaNueva) {
    detalle.push(`Moneda: ${cambio.monedaAnterior} → ${cambio.monedaNueva}`);
  }
  if (cambio.alicuotaAnterior && cambio.alicuotaNueva && cambio.alicuotaAnterior !== cambio.alicuotaNueva) {
    detalle.push(
      `IVA: ${ALICUOTA_IVA_LABELS[cambio.alicuotaAnterior]} → ${ALICUOTA_IVA_LABELS[cambio.alicuotaNueva]}`,
    );
  }
  if (!igualesLosAjustes(cambio.ajustesAnteriores, cambio.ajustesNuevos)) {
    detalle.push(
      `Descuentos y recargos: ${textoAjustes(cambio.ajustesAnteriores)} → ${textoAjustes(cambio.ajustesNuevos)}`,
    );
  }
  const costoReal = costoRealDe(cambio);
  if (costoReal) detalle.push(costoReal);
  return detalle;
}

/**
 * «Costo real: $ A → $ B (recargos N %)», como en la Actualización masiva (#97): el costo con los recargos del artículo
 * (CU-13), cada lado en su moneda; un solo importe si no cambia; null sin recargos.
 */
function costoRealDe(cambio: CambioPlaneado): string | null {
  const { recargosPorcentaje, costoRealAnterior, costoRealNuevo } = cambio;
  if (recargosPorcentaje == null || costoRealAnterior == null || costoRealNuevo == null) return null;
  const anterior = formatearCosto(costoRealAnterior, cambio.monedaAnterior);
  const nuevo = formatearCosto(costoRealNuevo, cambio.monedaNueva);
  const importes = anterior === nuevo ? nuevo : `${anterior} → ${nuevo}`;
  return `Costo real: ${importes} (recargos ${PORCENTAJE.format(recargosPorcentaje)} %)`;
}

/** Lo que una fila tiene de particular además de sus costos y precios: moneda, IVA, ajustes, barras, unidad o el motivo del error. */
export function detalleDeFila(fila: FilaDeVista): string[] {
  if (fila.cambio) return detalleDeCambio(fila.cambio);
  if (fila.tipo === 'conError') return fila.mensaje ? [fila.mensaje] : [];
  const alta = fila.alta;
  if (!alta) return [];
  const detalle: string[] = [];
  if (alta.moneda === 'USD') detalle.push('Moneda: USD');
  if (alta.alicuotaIva !== 'IVA_21') detalle.push(`IVA: ${ALICUOTA_IVA_LABELS[alta.alicuotaIva]}`);
  if (textoAjustes(alta.ajustes) !== 'ninguno') {
    detalle.push(`Descuentos y recargos: ${textoAjustes(alta.ajustes)}`);
  }
  if (alta.codigoBarras) detalle.push(`Código de barras: ${alta.codigoBarras}`);
  if (alta.unidad !== 'UNIDAD') detalle.push(`Unidad: ${UNIDAD_LABELS[alta.unidad]}`);
  return detalle;
}
