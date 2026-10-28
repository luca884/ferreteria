package com.ferreteria.facturacion.wsfe;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Los largos de los campos de WSFEv1 (manual 4.8, columna «Tipo»): {@code Int (n)} y {@code Long (n)} son n digitos como
 * mucho, {@code Double (e+d)} son e enteros y d decimales. Un pedido que no entra no se puede armar: es un error del que lo
 * arma, no algo que ARCA tenga que rechazar.
 */
final class Campos {

    private Campos() {
    }

    static <T> T requerido(String campo, T valor) {
        return Objects.requireNonNull(valor, () -> "Falta " + campo + ", que WSFEv1 exige.");
    }

    /** Un {@code Int (n)}: entre 0 y n nueves. */
    static int digitos(String campo, int valor, int maximo) {
        digitos(campo, (long) valor, maximo);
        return valor;
    }

    /** Un {@code Long (n)}: entre 0 y n nueves. */
    static long digitos(String campo, long valor, int maximo) {
        if (valor < 0 || String.valueOf(valor).length() > maximo) {
            throw new IllegalArgumentException(campo + " va en WSFEv1 con " + maximo + " dígitos como mucho, sin signo: "
                    + valor + " no entra.");
        }
        return valor;
    }

    /** Un {@code Double (enteros+decimales)}, devuelto con escala {@code decimales} (sin redondear: lo que no entra falla). */
    static BigDecimal importe(String campo, BigDecimal valor, int enteros, int decimales) {
        requerido(campo, valor);
        BigDecimal limpio = valor.stripTrailingZeros();
        int decimalesUsados = Math.max(limpio.scale(), 0);
        int enterosUsados = limpio.precision() - limpio.scale();
        if (decimalesUsados > decimales || enterosUsados > enteros) {
            throw new IllegalArgumentException(campo + " va en WSFEv1 como Double (" + enteros + "+" + decimales + "): "
                    + valor.toPlainString() + " no entra.");
        }
        return valor.setScale(decimales);
    }

    /** Un {@code String (n)} obligatorio, de n caracteres exactos. */
    static String texto(String campo, String valor, int largo) {
        requerido(campo, valor);
        if (valor.length() != largo) {
            throw new IllegalArgumentException(campo + " va en WSFEv1 con " + largo + " caracteres: «" + valor + "» no.");
        }
        return valor;
    }

    /** Un array opcional: ausente es vacío. */
    static <T> List<T> lista(List<T> valores) {
        return valores == null ? List.of() : List.copyOf(valores);
    }
}
