package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.facturacion.api.FacturacionParaVenta.CondicionIva;
import com.ferreteria.facturacion.api.FacturacionParaVenta.FacturacionDelMostrador;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que el cobro anticipa del comprobante (cu-07-factura-mostrador S3, C3): si la venta de ahora saldría con factura, el
 * ambiente (para «SIMULADO» u «HOMOLOGACIÓN») y la letra para cada condición frente al IVA del cliente, que arma
 * {@code facturacion} con su función de letra. Sin facturación (o con los datos de CU-01 incompletos): {@code factura}
 * false y lo demás null, que se manda igual.
 *
 * @param letras por condición: CONSUMIDOR_FINAL, RESPONSABLE_INSCRIPTO, MONOTRIBUTISTA y EXENTO
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FacturacionMostradorResponse(boolean factura, String ambiente, Map<String, String> letras) {

    static final FacturacionMostradorResponse SIN_FACTURA = new FacturacionMostradorResponse(false, null, null);

    static FacturacionMostradorResponse de(Optional<FacturacionDelMostrador> facturacion) {
        return facturacion.map(FacturacionMostradorResponse::de).orElse(SIN_FACTURA);
    }

    private static FacturacionMostradorResponse de(FacturacionDelMostrador f) {
        Map<String, String> letras = new LinkedHashMap<>();
        for (Map.Entry<CondicionIva, String> letra : f.letras().entrySet()) {
            letras.put(letra.getKey().name(), letra.getValue());
        }
        return new FacturacionMostradorResponse(true, f.ambiente(), letras);
    }
}
