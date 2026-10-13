package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.CambioMasivo;
import com.ferreteria.catalogo.model.ConteosMasivos;
import com.ferreteria.catalogo.model.ErrorMasivo;

import java.util.List;

/**
 * Lo que haria una actualizacion masiva. Los contadores son exactos; {@code cambios} y {@code errores}
 * traen como mucho las primeras 1.000 filas de cada una y {@code detalleRecortado} avisa si alguna se
 * cortó. La huella es la que se manda al aplicar.
 */
public record VistaPreviaMasivaResponse(
        ConteosMasivos conteos,
        List<CambioMasivo> cambios,
        List<ErrorMasivo> errores,
        boolean detalleRecortado,
        String huella) {
}
