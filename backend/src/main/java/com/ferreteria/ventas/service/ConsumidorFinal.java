package com.ferreteria.ventas.service;

import com.ferreteria.ferreteria.api.TopeConsumidorFinal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * GDS-32 (G88): desde el tope de la ferreteria (igual o superior, como lo dice ARCA), la venta facturable a Consumidor Final
 * necesita un cliente identificado, con DNI, CUIT o CUIL. Como el «Límite de Venta a Cons.Final sin datos» de GDS. La
 * venta no facturable no se controla: no va a ser un comprobante fiscal. Con la facturación habilitada (D4) rige el menor
 * entre el tope del Dueño y el de identificación de ARCA, y el mensaje dice el que rigió.
 */
@Component
public class ConsumidorFinal {

    private final TopeConsumidorFinal topes;

    public ConsumidorFinal(TopeConsumidorFinal topes) {
        this.topes = topes;
    }

    /**
     * {@code topeIdentificacion} es el tope de ARCA de la decisión de facturar, o null si la venta no sale con factura
     * (entonces rige solo el del Dueño). {@code total} es lo que se cobra, con el recargo de la forma de pago.
     */
    void verificar(boolean facturable, boolean identificado, BigDecimal total, BigDecimal topeIdentificacion) {
        if (!facturable || identificado) {
            return;
        }
        BigDecimal tope = topeVigente(topeIdentificacion);
        if (total.compareTo(tope) >= 0) {
            throw VentaRechazadaException.consumidorFinalSinDatos(importe(tope));
        }
    }

    /**
     * El tope que rige: el del Dueño o, si la venta sale con factura ({@code topeIdentificacion} no null), el menor con el de
     * ARCA. Lo usa también el cobro para avisar de entrada (#57).
     */
    public BigDecimal topeVigente(BigDecimal topeIdentificacion) {
        BigDecimal tope = topes.tope();
        return topeIdentificacion != null && topeIdentificacion.compareTo(tope) < 0 ? topeIdentificacion : tope;
    }

    /** «10.000.000,00», como se escriben los importes en Argentina. */
    private static String importe(BigDecimal valor) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(valor);
    }
}
