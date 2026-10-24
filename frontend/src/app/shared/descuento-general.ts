/**
 * El porcentaje que explica el descuento de los totales de una venta, un presupuesto o un remito. El importe `descuento`
 * suma el general y el de los renglones, así que el porcentaje general solo lo explica si ningún renglón tiene descuento
 * propio; si no, devuelve `null` y los totales dicen solo «Descuento».
 */
export function porcentajeDescuentoGeneral(documento: {
  descuentoPorcentaje: number;
  renglones: readonly { descuentoPorcentaje: number }[];
}): number | null {
  const conRenglones = documento.renglones.some((r) => r.descuentoPorcentaje > 0);
  return documento.descuentoPorcentaje > 0 && !conRenglones ? documento.descuentoPorcentaje : null;
}
