package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.RecargoCosto;

import java.math.BigDecimal;

/**
 * Un recargo del costo del articulo en el pedido (CU-13). No valida nada aca: {@link RecargoCosto} lo hace con los
 * mensajes del dominio y un 400 en {@code recargosCosto}.
 */
public record RecargoCostoRequest(String nombre, BigDecimal porcentaje) {

    RecargoCosto toRecargo() {
        return new RecargoCosto(nombre, porcentaje);
    }
}
