package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.facturacion.api.FacturacionParaVenta.CondicionIva;
import com.ferreteria.facturacion.api.FacturacionParaVenta.FacturacionDelMostrador;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lo que el cobro anticipa del comprobante (cu-07-factura-mostrador S3, C3): si la venta de ahora saldría con factura, el
 * ambiente (para «SIMULADO» u «HOMOLOGACIÓN») y la letra para cada condición frente al IVA del cliente, que arma
 * {@code facturacion} con su función de letra. Sin facturación (o con los datos de CU-01 incompletos): {@code factura}
 * false, ambiente y letras null, que se manda igual.
 *
 * @param letras por condición: CONSUMIDOR_FINAL, RESPONSABLE_INSCRIPTO, MONOTRIBUTISTA y EXENTO
 * @param topeConsumidorFinal desde este total la venta facturable sin cliente identificado se rechaza (GDS-32): el del
 *     Dueño o, con factura, el menor con el de ARCA. Viene siempre, así el cobro lo avisa de entrada (#57)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FacturacionMostradorResponse(boolean factura, String ambiente, Map<String, String> letras,
        BigDecimal topeConsumidorFinal) {

    /** {@code tope} da el tope que rige a partir del de ARCA (null sin factura): {@code ConsumidorFinal#topeVigente}. */
    static FacturacionMostradorResponse de(Optional<FacturacionDelMostrador> facturacion,
            Function<BigDecimal, BigDecimal> tope) {
        return facturacion.map(f -> de(f, tope.apply(f.topeIdentificacion())))
                .orElseGet(() -> new FacturacionMostradorResponse(false, null, null, tope.apply(null)));
    }

    private static FacturacionMostradorResponse de(FacturacionDelMostrador f, BigDecimal tope) {
        Map<String, String> letras = new LinkedHashMap<>();
        for (Map.Entry<CondicionIva, String> letra : f.letras().entrySet()) {
            letras.put(letra.getKey().name(), letra.getValue());
        }
        return new FacturacionMostradorResponse(true, f.ambiente(), letras, tope);
    }
}
