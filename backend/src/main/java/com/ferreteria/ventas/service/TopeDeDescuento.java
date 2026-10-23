package com.ferreteria.ventas.service;

import com.ferreteria.auth.api.TopesDeDescuento;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CU-07: el descuento no pasa el tope del usuario que confirma la venta, el presupuesto o el remito (como el «Limite de
 * Descuento» de GDS). Se mide el descuento efectivo de cada renglon, el del renglon y el global combinados, para que
 * 10 % + 10 % no esquive un tope de 10 %. Igual al tope entra. Sin tope (el Dueño nunca tiene) no se controla nada.
 */
@Component
public class TopeDeDescuento {

    private static final BigDecimal CIEN = new BigDecimal("100");

    private final TopesDeDescuento topes;

    public TopeDeDescuento(TopesDeDescuento topes) {
        this.topes = topes;
    }

    void verificar(List<Renglon> renglones, BigDecimal descuentoGlobal, Long usuarioId) {
        verificar(renglones, descuentoGlobal, usuarioId, Autorizados.NINGUNO);
    }

    /** Al pasar a venta un presupuesto o un remito, sus descuentos ya estan autorizados por quien lo emitio. */
    void verificar(List<Renglon> renglones, BigDecimal descuentoGlobal, Long usuarioId, Autorizados autorizados) {
        topes.topeDe(usuarioId).ifPresent(tope -> verificar(renglones, descuentoGlobal, tope, autorizados));
    }

    /**
     * Un global sobre el tope (y sobre el del documento) deja el error en el global; si no, en cada renglon que lo pasa.
     * El mensaje siempre nombra el tope del usuario.
     */
    static void verificar(List<Renglon> renglones, BigDecimal descuentoGlobal, BigDecimal tope,
            Autorizados autorizados) {
        String texto = tope.stripTrailingZeros().toPlainString().replace('.', ',');
        if (descuentoGlobal.compareTo(tope.max(autorizados.global())) > 0) {
            throw new DatosInvalidosException(Map.of("descuentoPorcentaje", "Tu tope de descuento es " + texto + " %."));
        }
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            BigDecimal propio = renglon.descuentoPorcentaje();
            BigDecimal delDocumento = autorizados.renglones().getOrDefault(renglon.articuloId(), BigDecimal.ZERO);
            BigDecimal efectivoDelDocumento = efectivo(delDocumento, autorizados.global());
            if (propio.compareTo(tope.max(delDocumento)) > 0) {
                errores.put("renglones[" + i + "].descuentoPorcentaje", "Tu tope de descuento es " + texto + " %.");
            } else if (efectivo(propio, descuentoGlobal).compareTo(tope.max(efectivoDelDocumento)) > 0) {
                errores.put("renglones[" + i + "].descuentoPorcentaje",
                        "Sumado al descuento general, pasa tu tope de descuento de " + texto + " %.");
            }
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** Los descuentos que ya trae el documento que se pasa a venta: el global y el de cada articulo. */
    record Autorizados(BigDecimal global, Map<Long, BigDecimal> renglones) {
        static final Autorizados NINGUNO = new Autorizados(BigDecimal.ZERO, Map.of());
    }

    /** 100 − (100 − r)(100 − g) / 100, exacto. */
    private static BigDecimal efectivo(BigDecimal renglon, BigDecimal global) {
        return CIEN.subtract(CIEN.subtract(renglon).multiply(CIEN.subtract(global)).divide(CIEN));
    }
}
