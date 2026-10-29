package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Armado de los tests de la bandeja de Ventas, las acciones del Dueño, los avisos del mostrador y «llegó el CAE»
 * (cu-07-contingencias C1 a C4 y C7), por HTTP contra MySQL real y el simulador. Mismo contexto que
 * {@link VentaFacturadaTestBase} (no suma configuración). Cada venta se cobra por HTTP, como el mostrador; los estados que
 * el simulador no da solo (fuera de fecha, en revisión, sin factura, salió con ticket provisorio) se ponen por SQL sobre el
 * registro vigente, respetando los CHECK de la V36.
 */
abstract class FacturaDelDuenoTestBase extends VentaFacturadaTestBase {

    static final String RI = "RESPONSABLE_INSCRIPTO";
    static final String VENTA_NO_ENCONTRADA = "Venta no encontrada";

    /** Cobra $ 121 como ese usuario, con ese cliente (o ninguno), y devuelve el id de la venta. */
    long cobrar(Tienda t, String email, Long clienteId) throws Exception {
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "100");
        JsonNode venta = json(postJson("/api/ventas", login(email),
                cuerpoDe(t, clienteId, null, clave(), "121.00", linea(a, "1", "121.00"))).andExpect(status().isCreated()));
        return venta.get("ventaId").asLong();
    }

    long cobrar(Tienda t) throws Exception {
        return cobrar(t, t.emailVendedor(), null);
    }

    /** Una venta cuya factura ARCA rechaza con esas observaciones (10015 si no se dice otra cosa). */
    long ventaRechazada(Tienda t, Long clienteId, Obs... observaciones) throws Exception {
        wsfe.fallas().rechazarConObservaciones(observaciones.length > 0 ? observaciones
                : new Obs[] {new Obs(10015, "El receptor no está identificado.")});
        long venta = cobrar(t, t.emailVendedor(), clienteId);
        assertThat(estadoFiscal(venta)).isEqualTo("RECHAZADO");
        return venta;
    }

    long ventaRechazada(Tienda t) throws Exception {
        return ventaRechazada(t, null);
    }

    /** Una venta cuyo pedido no llega a ARCA: queda PENDIENTE y sale con el ticket provisorio. */
    long ventaPendiente(Tienda t, String email) throws Exception {
        wsfe.fallas().programar(Falla.NO_LLEGA);
        long venta = cobrar(t, email, null);
        assertThat(estadoFiscal(venta)).isEqualTo("PENDIENTE");
        return venta;
    }

    long ventaAutorizada(Tienda t) throws Exception {
        long venta = cobrar(t);
        assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
        return venta;
    }

    /** PENDIENTE → FUERA_DE_FECHA, como lo deja el motor cuando la CbteFch ya no entra (sin número). */
    void fueraDeFecha(long venta) {
        assertThat(jdbc.update("update comprobante_fiscal set estado = 'FUERA_DE_FECHA' where comprobante_id = ? "
                + "and estado = 'PENDIENTE' and reemplazado_en is null", venta)).isOne();
    }

    /** AUTORIZADO → CAE_NO_COINCIDE, con su número: una alerta que resuelve soporte (S13). */
    void enRevision(long venta) {
        assertThat(jdbc.update("update comprobante_fiscal set estado = 'CAE_NO_COINCIDE', cae = null, cae_vencimiento = null, "
                + "resultado = null where comprobante_id = ? and estado = 'AUTORIZADO' and reemplazado_en is null", venta))
                .isOne();
    }

    /**
     * RECHAZADO → SIN_FACTURA, con el motivo, el usuario y la hora, guardada como la guarda Hibernate
     * ({@code jdbc.time_zone: UTC}).
     */
    void sinFactura(long venta, String motivo, long usuarioId, Instant en) {
        assertThat(jdbc.update("update comprobante_fiscal set estado = 'SIN_FACTURA', motivo_sin_factura = ?, "
                + "usuario_sin_factura_id = ?, sin_factura_en = ? where comprobante_id = ? and estado = 'RECHAZADO' "
                + "and reemplazado_en is null", motivo, usuarioId, LocalDateTime.ofInstant(en, ZoneOffset.UTC), venta))
                .isOne();
    }

    /** La venta salió con el ticket provisorio (lo marca el motor cuando vence la espera). */
    void provisoria(long venta) {
        assertThat(jdbc.update("update comprobante_fiscal set ticket_provisorio = true where comprobante_id = ? "
                + "and reemplazado_en is null", venta)).isOne();
    }

    long usuario(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    /** Un segundo Vendedor de la tienda (contraseña demo1234). */
    String otroVendedor(Tienda t) {
        String email = unico("vendedor2") + "@ventas.demo";
        String hash = jdbc.queryForObject("select password_hash from usuario where email = 'vendedor@tornillo.demo'",
                String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, "
                + "'Otro Vendedor', 'VENDEDOR')", t.id(), email, hash);
        return email;
    }

    String superAdmin() throws Exception {
        return login("admin@demo.local");
    }

    JsonNode detalle(Tienda t, long venta) throws Exception {
        return getJson("/api/ventas/" + venta, login(t.emailDueno()));
    }

    ResultActions reintentar(String token, long venta, String cuerpo) throws Exception {
        return postJson("/api/ventas/" + venta + "/factura/reintentar", token, cuerpo);
    }

    ResultActions sinFactura(String token, long venta, String cuerpo) throws Exception {
        return postJson("/api/ventas/" + venta + "/factura/sin-factura", token, cuerpo);
    }

    /** Los registros fiscales de la venta, el vigente al final, como los ve la base. */
    List<java.util.Map<String, Object>> registrosDe(long venta) {
        return jdbc.queryForList("select * from comprobante_fiscal where comprobante_id = ? order by id", venta);
    }

    static List<Long> ventaIds(JsonNode pagina) {
        List<Long> ids = new java.util.ArrayList<>();
        pagina.get("items").forEach(i -> ids.add(i.get("ventaId").asLong()));
        return ids;
    }
}
