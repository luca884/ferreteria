package com.ferreteria.common.exception;

import java.sql.SQLException;
import java.util.OptionalInt;

/**
 * Lee el codigo de error de MySQL de una excepcion de Spring o Hibernate (la SQLException va en algun lugar de la
 * cadena de causas). Sirve para no confundir un duplicado con un valor que no entra en la columna.
 */
public final class ErrorDeBase {

    /** ER_DUP_ENTRY: un UNIQUE repetido. */
    public static final int DUPLICADO = 1062;

    /** ER_WARN_DATA_OUT_OF_RANGE: el valor no entra en la columna (por ejemplo DECIMAL(15,2)). */
    public static final int FUERA_DE_RANGO = 1264;

    private ErrorDeBase() {
    }

    public static OptionalInt codigo(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() != 0) {
                return OptionalInt.of(sql.getErrorCode());
            }
        }
        return OptionalInt.empty();
    }

    public static boolean es(Throwable error, int codigo) {
        OptionalInt encontrado = codigo(error);
        return encontrado.isPresent() && encontrado.getAsInt() == codigo;
    }
}
