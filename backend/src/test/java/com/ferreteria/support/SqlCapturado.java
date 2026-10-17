package com.ferreteria.support;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Captura el SQL que Hibernate de verdad manda a la base (lote 5b, C-226), para probar el plan con EXPLAIN sobre ESA
 * consulta y no sobre una escrita a mano. Se registra en {@code spring.jpa.properties.hibernate.session_factory.
 * statement_inspector} del perfil test y solo guarda algo dentro de {@link #durante(Runnable)}: en el hilo que no esta
 * capturando no hace nada, asi que no cambia ningun otro test ni crea otro contexto de Spring.
 */
public class SqlCapturado implements StatementInspector {

    private static final ThreadLocal<List<String>> CAPTURA = new ThreadLocal<>();

    /** Ejecuta la accion y devuelve, en orden, el SQL que Hibernate preparo mientras corria (en este hilo). */
    public static List<String> durante(Runnable accion) {
        List<String> capturado = new ArrayList<>();
        CAPTURA.set(capturado);
        try {
            accion.run();
        } finally {
            CAPTURA.remove();
        }
        return capturado;
    }

    @Override
    public String inspect(String sql) {
        List<String> capturado = CAPTURA.get();
        if (capturado != null) {
            capturado.add(sql);
        }
        return sql;
    }
}
