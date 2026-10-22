package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * El precio que tendria un articulo con un costo nuevo, para una vista previa (compras). Si los ajustes sobre el precio
 * final lo dejarian en 0 o menos, el precio es el de hoy y viene el aviso de que no se cambia (S7).
 */
public record PrecioPlaneado(BigDecimal precio, Optional<String> aviso) {
}
