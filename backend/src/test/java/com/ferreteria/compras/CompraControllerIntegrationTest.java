package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La API de compras (CU-13): vista previa, registrar, listar y detalle. Solo el Dueno (el Vendedor recibe 403) y cada
 * ferreteria ve solo lo suyo (una compra, un proveedor o un articulo ajeno es 404). Margen 30 %, sin redondeo, IVA 21 %.
 */
class CompraControllerIntegrationTest extends IntegrationTestBase {

    private static final String AYER = LocalDate.now().minusDays(1).toString();

    Tienda tienda;
    String dueno;
    long proveedor;
    long tornillo;

    @BeforeEach
    void datos() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        proveedor = proveedor(tienda.id(), "Bulonera Sur");
        tornillo = articulo(tienda.id(), "TOR-1", "Tornillo 1/4", "100.00", "157.30");
    }

    @Test
    @DisplayName("la vista previa muestra costo y precio anteriores y nuevos, el neto y los conteos, sin guardar")
    void vistaPrevia() throws Exception {
        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 1234, tornillo, "20", "110.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorNombre").value("Bulonera Sur"))
                .andExpect(jsonPath("$.moneda").value("ARS"))
                .andExpect(jsonPath("$.neto").value(2200.00))
                .andExpect(jsonPath("$.suben").value(1))
                .andExpect(jsonPath("$.bajan").value(0))
                .andExpect(jsonPath("$.iguales").value(0))
                .andExpect(jsonPath("$.renglones[0].articuloId").value(tornillo))
                .andExpect(jsonPath("$.renglones[0].codigoInterno").value("TOR-1"))
                .andExpect(jsonPath("$.renglones[0].descripcion").value("Tornillo 1/4"))
                .andExpect(jsonPath("$.renglones[0].cantidad").value(20))
                .andExpect(jsonPath("$.renglones[0].costoAnterior").value(100.00))
                .andExpect(jsonPath("$.renglones[0].costoNuevo").value(110.00))
                .andExpect(jsonPath("$.renglones[0].precioAnterior").value(157.30))
                .andExpect(jsonPath("$.renglones[0].precioNuevo").value(173.03))
                .andExpect(jsonPath("$.renglones[0].subtotal").value(2200.00))
                .andExpect(jsonPath("$.renglones[0].advertencia").doesNotExist())
                .andExpect(jsonPath("$.renglones[0].stockAnterior").value(0))
                .andExpect(jsonPath("$.renglones[0].stockNuevo").value(20))
                .andExpect(jsonPath("$.renglones[0].alicuotaIva").value(21.0))
                .andExpect(jsonPath("$.subtotalSinIva").value(2200.00))
                .andExpect(jsonPath("$.recargosImporte").value(0))
                .andExpect(jsonPath("$.iva[0].porcentaje").value(21.0))
                .andExpect(jsonPath("$.iva[0].base").value(2200.00))
                .andExpect(jsonPath("$.iva[0].importe").value(462.00))
                .andExpect(jsonPath("$.total").value(2662.00));
        assertThat(jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class, tienda.id()))
                .isZero();
    }

    @Test
    @DisplayName("registrar responde 201 con la compra; la misma clave y el mismo contenido responden 200")
    void registrar() throws Exception {
        String cuerpo = cuerpo(UUID.randomUUID().toString(), 1234, tornillo, "20", "110.00");

        JsonNode creada = objectMapper.readTree(postJson("/api/compras", dueno, cuerpo)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comprobante").value("Factura A 00003-00001234"))
                .andExpect(jsonPath("$.tipo").value("FA"))
                .andExpect(jsonPath("$.puntoVenta").value(3))
                .andExpect(jsonPath("$.numero").value(1234))
                .andExpect(jsonPath("$.fecha").value(AYER))
                .andExpect(jsonPath("$.proveedorId").value(proveedor))
                .andExpect(jsonPath("$.proveedorNombre").value("Bulonera Sur"))
                .andExpect(jsonPath("$.neto").value(2200.00))
                .andExpect(jsonPath("$.renglones[0].renglon").value(1))
                .andExpect(jsonPath("$.renglones[0].costoUnitario").value(110.00))
                .andExpect(jsonPath("$.renglones[0].costoAnterior").value(100.00))
                .andExpect(jsonPath("$.renglones[0].precioNuevo").value(173.03))
                .andReturn().getResponse().getContentAsString());

        postJson("/api/compras", dueno, cuerpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(creada.get("id").asLong()));
    }

    @Test
    @DisplayName("el listado filtra por proveedor y fechas, cuenta los renglones y el detalle trae la compra")
    void listarYDetalle() throws Exception {
        long otro = proveedor(tienda.id(), "Pinturería Norte");
        long id = registrar(dueno, cuerpo(UUID.randomUUID().toString(), 1, tornillo, "1", "100.00"));
        registrar(dueno, cuerpo(UUID.randomUUID().toString(), otro, 2, tornillo, "1", "100.00"));

        JsonNode pagina = getJson("/api/compras?proveedorId=" + proveedor + "&desde=" + AYER + "&hasta=" + AYER, dueno);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(1);
        JsonNode resumen = pagina.get("items").get(0);
        assertThat(resumen.get("id").asLong()).isEqualTo(id);
        assertThat(resumen.get("comprobante").asText()).isEqualTo("Factura A 00003-00000001");
        assertThat(resumen.get("proveedorNombre").asText()).isEqualTo("Bulonera Sur");
        assertThat(resumen.get("renglones").asInt()).isEqualTo(1);
        assertThat(getJson("/api/compras", dueno).get("totalElements").asLong()).isEqualTo(2);
        assertThat(getJson("/api/compras?desde=" + LocalDate.now(), dueno).get("totalElements").asLong()).isZero();

        getResult("/api/compras/" + id, dueno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.renglones[0].descripcion").value("Tornillo 1/4"));
    }

    @Test
    @DisplayName("el Vendedor no puede ver ni registrar compras: 403")
    void vendedorRecibe403() throws Exception {
        String vendedor = login(tienda.emailVendedor());
        long id = registrar(dueno, cuerpo(UUID.randomUUID().toString(), 1, tornillo, "1", "100.00"));

        postJson("/api/compras/vista-previa", vendedor, cuerpo(null, 2, tornillo, "1", "100.00"))
                .andExpect(status().isForbidden());
        postJson("/api/compras", vendedor, cuerpo(UUID.randomUUID().toString(), 2, tornillo, "1", "100.00"))
                .andExpect(status().isForbidden());
        getResult("/api/compras", vendedor).andExpect(status().isForbidden());
        getResult("/api/compras/" + id, vendedor).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("otra ferreteria no ve la compra, ni puede usar su proveedor ni sus articulos: 404")
    void aislamiento() throws Exception {
        long id = registrar(dueno, cuerpo(UUID.randomUUID().toString(), 1, tornillo, "1", "100.00"));
        Tienda otra = tienda();
        String duenoOtra = login(otra.emailDueno());
        long proveedorOtra = proveedor(otra.id(), "Otro proveedor");
        long articuloOtra = articulo(otra.id(), "TOR-1", "Tornillo otra", "100.00", "157.30");

        getResult("/api/compras/" + id, duenoOtra)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Compra no encontrada"));
        assertThat(getJson("/api/compras", duenoOtra).get("totalElements").asLong()).isZero();
        postJson("/api/compras", duenoOtra, cuerpo(UUID.randomUUID().toString(), proveedor, 9, articuloOtra, "1", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Proveedor no encontrado"));
        postJson("/api/compras", duenoOtra, cuerpo(UUID.randomUUID().toString(), proveedorOtra, 9, tornillo, "1", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Artículo no encontrado"));
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", String.class, tornillo))
                .isEqualTo("100.00");
    }

    @Test
    @DisplayName("la forma del pedido se valida campo por campo: 400")
    void validacionDeForma() throws Exception {
        postJson("/api/compras", dueno, cuerpo(null, 1, tornillo, "1", "100.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clave").value("Falta la clave de la compra."));
        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 0, tornillo, "0", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.numero").value("El número va de 1 a 99999999."))
                .andExpect(jsonPath("$.errors['renglones[0].cantidad']").value("La cantidad tiene que ser mayor a 0."))
                .andExpect(jsonPath("$.errors['renglones[0].costoUnitario']").value("El costo tiene que ser mayor a 0."));
        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 1, tornillo, "1", "10.123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['renglones[0].costoUnitario']").value("El costo admite hasta 2 decimales."));
    }

    @Test
    @DisplayName("un tipo de comprobante invalido da su mensaje, no el de los movimientos de caja")
    void tipoDeComprobanteInvalido() throws Exception {
        String mensaje = "El tipo de comprobante no es válido. Usá FA, FB, FC o REM.";
        String cuerpo = cuerpo(null, 1, tornillo, "1", "100.00").replace("\"FA\"", "\"OTRO\"");

        postJson("/api/compras/vista-previa", dueno, cuerpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(mensaje))
                .andExpect(jsonPath("$.errors.tipo").value(mensaje));
        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 1, tornillo, "1", "100.00")
                        .replace("\"FA\"", "\"OTRO\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tipo").value(mensaje));
    }

    // ---- helpers ----

    record Tienda(long id, String emailDueno, String emailVendedor) {}

    private long registrar(String token, String cuerpo) throws Exception {
        String respuesta = postJson("/api/compras", token, cuerpo)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private String cuerpo(String clave, long numero, long articuloId, String cantidad, String costo) {
        return cuerpo(clave, proveedor, numero, articuloId, cantidad, costo);
    }

    private static String cuerpo(String clave, long proveedorId, long numero, long articuloId, String cantidad,
                                 String costo) {
        return """
                {%s"proveedorId": %d, "tipo": "FA", "puntoVenta": 3, "numero": %d, "fecha": "%s",
                 "renglones": [{"articuloId": %d, "cantidad": %s, "costoUnitario": %s}]}
                """.formatted(clave == null ? "" : "\"clave\": \"" + clave + "\", ", proveedorId, numero, AYER,
                articuloId, cantidad, costo);
    }

    private Tienda tienda() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String cuit = "30-7%07d-%d".formatted(ThreadLocalRandom.current().nextInt(10_000_000),
                ThreadLocalRandom.current().nextInt(10));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Compras " + sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        String dueno = "dueno-" + sufijo + "@compras.demo";
        String vendedor = "vendedor-" + sufijo + "@compras.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Compras', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Compras', 'VENDEDOR')", id, vendedor, hash);
        return new Tienda(id, dueno, vendedor);
    }

    private long proveedor(long ferreteriaId, String nombre) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, ?, true)", ferreteriaId, nombre);
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?",
                Long.class, ferreteriaId, nombre);
    }

    private long articulo(long ferreteriaId, String codigo, String descripcion, String costo, String precio) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                        + "alicuota_iva, stock_actual, activo) values (?, ?, ?, ?, ?, 'UNIDAD', 'IVA_21', 0, true)",
                ferreteriaId, codigo, descripcion, costo, precio);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }
}
