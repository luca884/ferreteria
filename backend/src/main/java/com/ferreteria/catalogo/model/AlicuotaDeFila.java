package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Alicuota de IVA de una fila de la lista (decision 7). Siempre es una de las seis legales:
 * <ul>
 *   <li>con la columna de % IVA, el valor se toma tal cual y debe ser legal; si no, la fila da error;</li>
 *   <li>con las columnas sin IVA y con IVA (que tienen que estar en la misma base) se toma la legal mas cercana a
 *       con / sin, y se advierte si la diferencia pasa de 0,5 puntos;</li>
 *   <li>si estan las dos formas manda el % IVA; si la celda de % IVA esta vacia se usa el par;</li>
 *   <li>sin dato, el articulo nuevo entra con IVA_21 y el existente conserva la suya.</li>
 * </ul>
 */
public final class AlicuotaDeFila {

    private static final String VALIDAS = "Las alícuotas válidas son 0, 2,5, 5, 10,5, 21 y 27.";

    private AlicuotaDeFila() {
    }

    /** Alicuota, sin dato o error; el valor puede traer ademas una advertencia (el error no). */
    public record ResultadoAlicuota(AlicuotaIva alicuota, boolean sinDato, String mensaje, String advertencia) {

        static ResultadoAlicuota valor(AlicuotaIva alicuota, String advertencia) {
            return new ResultadoAlicuota(alicuota, false, null, advertencia);
        }

        static ResultadoAlicuota ausente() {
            return new ResultadoAlicuota(null, true, null, null);
        }

        static ResultadoAlicuota error(String mensaje) {
            return new ResultadoAlicuota(null, false, mensaje, null);
        }

        public boolean esValor() {
            return alicuota != null;
        }

        public boolean esSinDato() {
            return sinDato;
        }

        public boolean esError() {
            return mensaje != null;
        }
    }

    /**
     * Cada celda es la de la fila en la columna correspondiente, o null si esa columna no esta mapeada. El par de
     * IVA (sin y con) se usa solo si estan las dos.
     */
    public static ResultadoAlicuota interpretar(Celda porcentajeIva, Celda sinIva, Celda conIva) {
        if (porcentajeIva != null) {
            ParserPorcentaje.Resultado porcentaje = ParserPorcentaje.parsear(porcentajeIva);
            if (porcentaje.esInvalido()) {
                return ResultadoAlicuota.error("El IVA «" + porcentajeIva.texto().strip() + "» no es un número. " + VALIDAS);
            }
            if (porcentaje.esValor()) {
                return AlicuotaIva.legal(porcentaje.valor())
                    .map(alicuota -> ResultadoAlicuota.valor(alicuota, null))
                    .orElseGet(() -> ResultadoAlicuota.error(
                        "El IVA " + texto(porcentaje.valor()) + " no es válido. " + VALIDAS));
            }
        }
        if (sinIva != null && conIva != null) {
            return derivar(sinIva, conIva);
        }
        return ResultadoAlicuota.ausente();
    }

    private static ResultadoAlicuota derivar(Celda sinIva, Celda conIva) {
        if (sinIva.vacia() || conIva.vacia()) {
            return ResultadoAlicuota.ausente();
        }
        Optional<BigDecimal> sin = ParserImporte.parsear(sinIva);
        if (sin.isEmpty()) {
            return ResultadoAlicuota.error("El precio sin IVA no es un número.");
        }
        Optional<BigDecimal> con = ParserImporte.parsear(conIva);
        if (con.isEmpty()) {
            return ResultadoAlicuota.error("El precio con IVA no es un número.");
        }
        if (sin.get().signum() <= 0) {
            // No hay con que dividir; el costo cero lo informa otra regla.
            return ResultadoAlicuota.ausente();
        }
        AlicuotaIva.Aproximacion aproximacion = AlicuotaIva.masCercana(sin.get(), con.get());
        String advertencia = aproximacion.advertir()
            ? "El IVA calculado con los precios de la fila es " + texto(aproximacion.calculado()) + " %; se usó "
                + texto(aproximacion.alicuota().porcentaje()) + " %."
            : null;
        return ResultadoAlicuota.valor(aproximacion.alicuota(), advertencia);
    }

    /** 21,00 se escribe 21 y 10,50 se escribe 10,5, con coma. */
    private static String texto(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
