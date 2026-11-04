package com.ferreteria.catalogo.api;

import java.math.BigDecimal;

/**
 * Un recargo con nombre sobre el costo que trae una compra (CU-13), por ejemplo «Embalaje y carga 4,44 %». Solo tipos del
 * JDK: catalogo lo valida y lo convierte en su recargo del costo ({@link ArticulosParaCompra#validarRecargos}).
 */
public record RecargoDeCompra(String nombre, BigDecimal porcentaje) {
}
