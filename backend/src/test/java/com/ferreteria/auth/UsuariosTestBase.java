package com.ferreteria.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base de los tests de la gestion de usuarios (CU-02): cada test crea su propia ferreteria por JDBC, porque la base se
 * comparte entre tests y no se revierte. Los usuarios entran con demo1234, igual que los de los datos demo.
 */
abstract class UsuariosTestBase extends IntegrationTestBase {

    /** Hash BCrypt de demo1234, el mismo de los datos demo. */
    static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";
    private static final AtomicInteger CUITS = new AtomicInteger(75000000);

    protected long crearFerreteria(String prefijo) {
        String cuit = String.format("30-%08d-9", CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                unico(prefijo), cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    /** Inserta un usuario con contraseña demo1234 y devuelve su id. */
    protected long crearUsuario(long ferreteria, String email, String nombre, String rol) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, ?, ?)",
                ferreteria, email, HASH_DEMO, nombre, rol);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    protected ResultActions intentarLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("email", email).put("password", password).toString()));
    }

    protected static String json(Object... paresClaveValor) {
        var nodo = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (int i = 0; i < paresClaveValor.length; i += 2) {
            Object valor = paresClaveValor[i + 1];
            String clave = (String) paresClaveValor[i];
            if (valor == null) {
                nodo.putNull(clave);
            } else if (valor instanceof Boolean b) {
                nodo.put(clave, b);
            } else if (valor instanceof Number n) {
                nodo.put(clave, n.longValue());
            } else {
                nodo.put(clave, valor.toString());
            }
        }
        return nodo.toString();
    }
}
