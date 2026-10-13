package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de exito de CU-05 (R-019): vista previa + aplicar sobre 5.000 articulos en menos de 2 minutos.
 * Corre en una ferreteria PROPIA (creada por jdbc, con un Dueno que reusa la contrasena demo) para no
 * ensuciar a los tenants demo. El armado de los datos no se cronometra: se mide solo vista previa + aplicar,
 * pasando por el filtro de seguridad, el planificador y la transaccion real.
 */
class ActualizacionMasivaRendimientoIntegrationTest extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ActualizacionMasivaRendimientoIntegrationTest.class);

    private static final Duration LIMITE = Duration.ofSeconds(120);
    private static final int ARTICULOS = 5_000;

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void cincoMilArticulosConCostoMasDiezPorCientoSeVenYSeAplicanEnMenosDeDosMinutos() throws Exception {
        String cuit = "30-72000051-5";
        String email = "dueno@rendimiento-masivo.demo";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Rendimiento masivo " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Rendimiento', 'DUENO')", ferreteriaId, email, hash);
        long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor grande')", ferreteriaId);
        long proveedorId = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ?", Long.class, ferreteriaId);

        // 5.000 articulos del proveedor, costo 100.00 y precio 160.00 (margen heredado 30 % + IVA 21 %, multiplo 10).
        List<Object[]> filas = new ArrayList<>(ARTICULOS);
        for (int i = 1; i <= ARTICULOS; i++) {
            String codigo = String.format("M-%05d", i);
            filas.add(new Object[] {ferreteriaId, codigo, "Articulo " + codigo, proveedorId});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, costo, "
                + "alicuota_iva, precio_final, unidad) values (?, ?, ?, ?, 100.00, 'IVA_21', 160.00, 'UNIDAD')", filas);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ?", ferreteriaId)).isEqualTo(ARTICULOS);

        String token = login(email);
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("modo", "COSTO_PORCENTAJE").put("valor", new BigDecimal("10")).put("proveedorId", proveedorId);

        long inicio = System.nanoTime();
        String cuerpoVistaPrevia = postJson("/api/actualizacion-masiva/vista-previa", token, pedido.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long trasVistaPrevia = System.nanoTime();
        JsonNode vistaPrevia = objectMapper.readTree(cuerpoVistaPrevia);
        ObjectNode aplicar = pedido.deepCopy().put("huella", vistaPrevia.get("huella").asText());
        String cuerpoAplicada = postJson("/api/actualizacion-masiva", token, aplicar.toString())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fin = System.nanoTime();

        Duration total = Duration.ofNanos(fin - inicio);
        LOG.info("RENDIMIENTO CU-05 (5.000 articulos, COSTO_PORCENTAJE +10, los 5.000 cambian): "
                        + "vista previa {} ms, aplicar {} ms, total {} ms (limite {} ms)",
                Duration.ofNanos(trasVistaPrevia - inicio).toMillis(), Duration.ofNanos(fin - trasVistaPrevia).toMillis(),
                total.toMillis(), LIMITE.toMillis());

        JsonNode aplicada = objectMapper.readTree(cuerpoAplicada);
        assertThat(vistaPrevia.get("conteos").get("total").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("conteos").get("modificados").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("detalleRecortado").asBoolean()).isTrue();
        assertThat(aplicada.get("conteos").get("modificados").asInt()).isEqualTo(ARTICULOS);
        assertThat(aplicada.get("entradasDeHistorial").asInt()).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and costo = 110.00 "
                + "and precio_final = 180.00", ferreteriaId)).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO' "
                + "and usuario_id = ?", ferreteriaId, usuarioId)).isEqualTo(ARTICULOS);
        assertThat(total).as("vista previa + aplicar de 5.000 articulos").isLessThan(LIMITE);
    }
}
