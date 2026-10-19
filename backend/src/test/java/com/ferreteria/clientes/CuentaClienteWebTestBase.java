package com.ferreteria.clientes;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.InvarianteCuenta;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Armado común de los tests HTTP de la cuenta corriente de clientes (corte 4): cada test crea su PROPIA ferretería con Dueño y
 * Vendedor (la base no se revierte) y sus clientes con el límite y el saldo que necesita. El CUIT es 30-83NNNNNN-1: NN es el
 * bloque de la clase y lo que sigue una secuencia, así que no choca con otras clases ni entre tests.
 */
abstract class CuentaClienteWebTestBase extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /** Una ferretería de prueba con su Dueño y su Vendedor. */
    protected record Tienda(long id, String emailDueno, String emailVendedor) {}

    /** Bloque de CUIT de la clase (30-83NN....): lo define cada clase concreta. */
    protected abstract String bloqueCuit();

    protected Tienda tienda() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String cuit = "30-%s%04d-1".formatted(bloqueCuit(), SECUENCIA.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Cuenta cliente " + sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject("select password_hash from usuario where email = 'vendedor@tornillo.demo'",
                String.class);
        String dueno = "dueno-" + sufijo + "@cuentacliente.demo";
        String vendedor = "vendedor-" + sufijo + "@cuentacliente.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Cuenta', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Cuenta', 'VENDEDOR')", id, vendedor, hash);
        return new Tienda(id, dueno, vendedor);
    }

    /** Un cliente de la tienda con ese límite (null = sin cuenta corriente) y ese saldo (con su movimiento en el libro). */
    protected long cliente(Tienda t, String limite, String saldo) {
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", t.id(), razon);
        long id = jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
        SemillaCuentaCliente.sembrar(jdbc, t.id(), id, limite, saldo);
        return id;
    }

    protected JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected ResultActions patchActivo(String token, long id, boolean activo) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch("/api/clientes/" + id + "/activo")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\": " + activo + "}"));
    }

    protected BigDecimal saldo(long clienteId) {
        return jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
    }

    protected BigDecimal limite(long clienteId) {
        return jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, clienteId);
    }

    protected long movimientos(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class,
                clienteId);
    }

    protected void assertInvariante(long clienteId) {
        InvarianteCuenta.assertInvariante(jdbc, clienteId);
    }
}
