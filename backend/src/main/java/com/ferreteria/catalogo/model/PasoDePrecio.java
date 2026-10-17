package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Un paso del desglose: el importe al que se llega en ese paso. Los intermedios van a 4 decimales solo para
 * mostrarlos; el costo y el precio final van a 2.
 *
 * @param porcentaje el porcentaje del paso (bonificacion, ajuste, IVA, margen), o null si no tiene
 */
public record PasoDePrecio(TipoPaso tipo, BigDecimal porcentaje, BigDecimal importe) {
}
