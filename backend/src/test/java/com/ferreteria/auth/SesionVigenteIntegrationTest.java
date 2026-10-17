package com.ferreteria.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El JWT dura 12 horas: sin mirar la base, un usuario dado de baja o una ferreteria suspendida seguian operando
 * hasta que vencia. Cada request vuelve a comprobar que el usuario y su ferreteria esten activos y que el rol del
 * token sea el vigente. Usa una ferreteria propia: la base se comparte entre tests y no se revierte.
 */
class SesionVigenteIntegrationTest extends IntegrationTestBase {

    /** Hash BCrypt de demo1234, el mismo de los datos demo. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";
    private static final AtomicInteger CUITS = new AtomicInteger();

    private Long ferreteria;
    private String emailDueno;
    private String emailVendedor;

    @BeforeEach
    void crearFerreteria() {
        String cuit = String.format("30-%08d-9", 74000000 + CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                unico("Sesion vigente"), cuit);
        ferreteria = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        emailDueno = unico("dueno") + "@sesion.test";
        emailVendedor = unico("vendedor") + "@sesion.test";
        crearUsuario(emailDueno, "DUENO");
        crearUsuario(emailVendedor, "VENDEDOR");
    }

    private void crearUsuario(String email, String rol) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, ?, ?)",
                ferreteria, email, HASH_DEMO, "Usuario " + rol, rol);
    }

    @Test
    @DisplayName("con todo activo el token sirve")
    void vigente() throws Exception {
        getResult("/api/rubros", login(emailDueno)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("un usuario dado de baja con el token ya emitido recibe 401 en el request siguiente")
    void usuarioDadoDeBaja() throws Exception {
        String token = login(emailVendedor);
        jdbc.update("update usuario set activo = false where email = ?", emailVendedor);

        getResult("/api/articulos/mostrador?q=tornillo", token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una ferreteria suspendida corta a todos sus usuarios con el token ya emitido")
    void ferreteriaSuspendida() throws Exception {
        String dueno = login(emailDueno);
        String vendedor = login(emailVendedor);
        jdbc.update("update ferreteria set estado = 'SUSPENDIDA' where id = ?", ferreteria);

        getResult("/api/rubros", dueno).andExpect(status().isUnauthorized());
        getResult("/api/articulos/mostrador?q=tornillo", vendedor).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("si al dueno le cambian el rol a vendedor, su token de dueno deja de servir")
    void rolCambiado() throws Exception {
        String token = login(emailDueno);
        jdbc.update("update usuario set rol = 'VENDEDOR' where email = ?", emailDueno);

        getResult("/api/rubros", token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("el super admin, sin ferreteria, sigue entrando")
    void superAdmin() throws Exception {
        getResult("/api/auth/me", login("admin@demo.local")).andExpect(status().isOk());
    }
}
