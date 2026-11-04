package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Oferta;
import com.ferreteria.catalogo.model.TipoOferta;
import com.ferreteria.common.exception.DatosInvalidosException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * La oferta que pone el Dueño (ofertas, S1): tipo, valor y fecha de fin opcional, con la version del articulo que leyo.
 * Las reglas de negocio (rango, que baje el precio, la fecha) las valida {@link com.ferreteria.catalogo.model.Articulo}.
 */
public record OfertaRequest(TipoOferta tipo, BigDecimal valor, LocalDate hasta, Long version) {

    static final String VALOR_OBLIGATORIO = "El valor tiene que ser mayor a 0.";

    /** La oferta del pedido, o 400 en {@code tipo} y {@code valor} si faltan. */
    public Oferta toOferta() {
        Map<String, String> errores = new LinkedHashMap<>();
        if (tipo == null) {
            errores.put("tipo", TipoOferta.MENSAJE_INVALIDO);
        }
        if (valor == null) {
            errores.put("valor", VALOR_OBLIGATORIO);
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
        return new Oferta(tipo, valor, hasta);
    }
}
