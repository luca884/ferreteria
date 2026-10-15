package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Armado comun de los tests de integracion de CU-07: cada test crea su PROPIA ferreteria (con Dueno y
 * Vendedor) y sus articulos por jdbc, porque la base no se revierte y los datos demo no se escriben.
 * El CUIT es aleatorio (prefijo 30-8) para no chocar con {@code uk_ferreteria_cuit} de otras clases.
 */
abstract class VentasTestBase extends IntegrationTestBase {

    /** Una ferreteria de prueba con su Dueno y su Vendedor. */
    record Tienda(long id, String emailDueno, String emailVendedor) {}

    /** Un renglon del pedido tal como lo manda el mostrador. */
    record Linea(long articuloId, String cantidad, String precio) {}

    protected Tienda tienda() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String cuit = "30-8%07d-%d".formatted(ThreadLocalRandom.current().nextInt(10_000_000),
                ThreadLocalRandom.current().nextInt(10));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Ventas " + sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        String dueno = "dueno-" + sufijo + "@ventas.demo";
        String vendedor = "vendedor-" + sufijo + "@ventas.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Ventas', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Ventas', 'VENDEDOR')", id, vendedor, hash);
        return new Tienda(id, dueno, vendedor);
    }

    protected long articulo(Tienda tienda, String descripcion, String unidad, String precio, String stock) {
        return articulo(tienda, descripcion, unidad, precio, stock, "IVA_21", true);
    }

    protected long articulo(Tienda tienda, String descripcion, String unidad, String precio, String stock,
            String alicuota, boolean activo) {
        String codigo = "V-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, ?, ?, 1.00, ?, ?, ?, ?, 0, ?)",
                tienda.id(), codigo, descripcion, new BigDecimal(precio), alicuota, unidad, new BigDecimal(stock),
                activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, tienda.id(), codigo);
    }

    protected static String clave() {
        return UUID.randomUUID().toString();
    }

    protected static Linea linea(long articuloId, String cantidad, String precio) {
        return new Linea(articuloId, cantidad, precio);
    }

    /** El cuerpo del POST. Los importes viajan como numeros JSON (no como strings). */
    protected String cuerpo(String clave, String medio, String total, Linea... lineas) throws Exception {
        return objectMapper.writeValueAsString(mapa(clave, medio, total, lineas));
    }

    protected Map<String, Object> mapa(String clave, String medio, String total, Linea... lineas) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave);
        List<Map<String, Object>> renglones = new ArrayList<>();
        for (Linea l : lineas) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("articuloId", l.articuloId());
            r.put("cantidad", new BigDecimal(l.cantidad()));
            r.put("precioUnitario", new BigDecimal(l.precio()));
            renglones.add(r);
        }
        cuerpo.put("renglones", renglones);
        cuerpo.put("totalMostrado", total == null ? null : new BigDecimal(total));
        cuerpo.put("medioPago", medio);
        return cuerpo;
    }

    protected ResultActions vender(String token, String clave, String total, Linea... lineas) throws Exception {
        return postJson("/api/ventas", token, cuerpo(clave, "EFECTIVO", total, lineas));
    }

    protected JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected long comprobantes(Tienda tienda) {
        return jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, tienda.id());
    }

    protected long movimientos(Tienda tienda) {
        return jdbc.queryForObject("select count(*) from movimiento_stock where ferreteria_id = ?", Long.class,
                tienda.id());
    }

    protected BigDecimal stock(long articuloId) {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, articuloId);
    }
}
