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

    /**
     * La ultima ferreteria que armo {@link #tiendaSinCaja()}: de ella sale la forma de pago de los cuerpos que se
     * arman sin pasarle la tienda (cuerpo, mapa, vender). Un test con dos tiendas pasa la que corresponde a cada una.
     */
    protected Tienda ultimaTienda;

    /** Un renglon del pedido tal como lo manda el mostrador. */
    record Linea(long articuloId, String cantidad, String precio) {}

    /**
     * Una ferreteria lista para vender: con la caja abierta hoy (CU-10), como la abre el cajero al llegar. Sin caja no se
     * vende; esa regla la prueban {@code VentaSinCajaIntegrationTest} y {@code CajaIntegrationTest} con
     * {@link #tiendaSinCaja()}.
     */
    protected Tienda tienda() {
        Tienda tienda = tiendaSinCaja();
        cajaAbiertaHoy(tienda.id());
        return tienda;
    }

    /** Una ferreteria recien creada, sin ninguna caja. */
    protected Tienda tiendaSinCaja() {
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
        ultimaTienda = new Tienda(id, dueno, vendedor);
        return ultimaTienda;
    }

    /**
     * La forma de pago de siempre de ese medio (Efectivo, Debito, Credito, Transferencia o Cuenta corriente) de la tienda.
     * Si la tienda todavia no tiene ninguna forma las carga, igual que el servicio la primera vez que las pide: las cuatro
     * de siempre activas y «Cuenta corriente» INACTIVA (orden 5, recargo 0). {@code formaDe(tienda, "CUENTA_CORRIENTE")}
     * devuelve su id aun inactiva: sirve para probar el 400 por forma inactiva. Un medio que no existe (por ejemplo
     * "CHEQUE") da el id de una forma que tampoco existe: sirve para probar el rechazo.
     */
    protected long formaDe(Tienda tienda, String medio) {
        Long cargadas = jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class,
                tienda.id());
        if (cargadas == 0) {
            jdbc.update("insert ignore into forma_pago (ferreteria_id, nombre, medio, orden) values "
                    + "(?, 'Efectivo', 'EFECTIVO', 1), (?, 'Débito', 'DEBITO', 2), (?, 'Crédito', 'CREDITO', 3), "
                    + "(?, 'Transferencia', 'TRANSFERENCIA', 4)", tienda.id(), tienda.id(), tienda.id(), tienda.id());
            jdbc.update("insert ignore into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                    + "values (?, 'Cuenta corriente', 'CUENTA_CORRIENTE', 0, 0, 5)", tienda.id());
        }
        Long id = jdbc.queryForObject("select min(id) from forma_pago where ferreteria_id = ? and medio = ?", Long.class,
                tienda.id(), medio);
        return id != null ? id : 999_999_999L;
    }

    /** Deja ACTIVA la forma «Cuenta corriente» de la tienda (la carga inactiva si todavia no hay formas), como el Dueño. */
    protected void activarFormaCuentaCorriente(Tienda tienda) {
        long id = formaDe(tienda, "CUENTA_CORRIENTE");
        jdbc.update("update forma_pago set activa = 1 where id = ? and ferreteria_id = ?", id, tienda.id());
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
        cuerpo.put("formaPagoId", medio == null ? null : formaDe(ultimaTienda, medio));
        return cuerpo;
    }

    /** Cobra en efectivo con la forma de siempre de la ferreteria del token (la que la API le lista a ese usuario). */
    protected ResultActions vender(String token, String clave, String total, Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo));
    }

    /** Emite un presupuesto de un renglon (articulo y cantidad) al precio que se le pasa y devuelve la respuesta. */
    protected JsonNode presupuesto(String token, long articuloId, String cantidad, String precio, String total)
            throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articuloId);
        renglon.put("cantidad", new BigDecimal(cantidad));
        renglon.put("precioUnitario", new BigDecimal(precio));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(total));
        return json(postJson("/api/presupuestos", token, objectMapper.writeValueAsString(cuerpo))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
    }

    /** Emite un remito de un renglon (articulo y cantidad) al precio que se le pasa y devuelve la respuesta. */
    protected JsonNode remito(String token, long articuloId, String cantidad, String precio, String total)
            throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articuloId);
        renglon.put("cantidad", new BigDecimal(cantidad));
        renglon.put("precioUnitario", new BigDecimal(precio));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(total));
        return json(postJson("/api/remitos", token, objectMapper.writeValueAsString(cuerpo))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
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
