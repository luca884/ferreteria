package com.ferreteria.ventas.model;

import java.math.BigDecimal;

/**
 * Las ventas de una caja que despues se anularon (NC o anulacion interna), cuantas y su total: el renglon informativo del
 * resumen de la caja (hallazgos-ferreteria-de-prueba T1). La anulacion no mueve la caja (CU-08), asi que no entra en
 * ningun total: solo explica por que «Total de ventas» es bruto.
 */
public record VentasAnuladas(long cantidad, BigDecimal total) {}
