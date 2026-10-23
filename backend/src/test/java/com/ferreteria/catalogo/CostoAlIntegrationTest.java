package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Costo al» (GDS-05, como «F. Modif.» de GDS G22): desde cuando esta al dia el costo de un articulo. Es la fecha mas
 * nueva entre el ultimo cambio de costo o de moneda del historial, la ultima importacion de lista que lo toco (aunque
 * haya confirmado el mismo costo) y el alta. Lo de otra ferreteria no cuenta. CUIT 30-79093001-7 y 30-79093002-8.
 */
class CostoAlIntegrationTest extends AjustesTestBase {

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private Long usuarioA;
    private Long usuarioB;
    private Long proveedorA;
    private Long proveedorB;
    private final Map<String, Long> ids = new HashMap<>();

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79093001-7", "Costo al A");
        b = ferreteria("30-79093002-8", "Costo al B");
        limpiar();
        duenoA = login(usuario(a, "dueno-costoal-a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-costoal-a@test.local", "VENDEDOR"));
        usuario(b, "dueno-costoal-b@test.local", "DUENO");
        usuarioA = idDeUsuario("dueno-costoal-a@test.local");
        usuarioB = idDeUsuario("dueno-costoal-b@test.local");
        proveedorA = proveedor(a, "Proveedor costo al");
        proveedorB = proveedor(b, "Proveedor ajeno costo al");

        // Solo el alta: no tiene historial ni importacion.
        cargar("CAL-1", "costoal solo alta");
        // Cambio de costo el 5/3; despues un historial que solo cambio el precio (cotizacion), que no cuenta.
        cargar("CAL-2", "costoal cambio de costo");
        historial(a, "CAL-2", "100.00", "120.00", "ARS", null, "2026-03-05 10:00:00");
        historial(a, "CAL-2", "120.00", "120.00", "ARS", "ARS", "2026-04-01 10:00:00");
        // Cambio de costo el 1/2, pero una lista del proveedor lo confirmo el 20/5 sin cambiarlo (no deja historial).
        cargar("CAL-3", "costoal confirmado por lista");
        historial(a, "CAL-3", "100.00", "110.00", "ARS", "ARS", "2026-02-01 10:00:00");
        marcarImportacion("CAL-3", importacion(a, usuarioA, proveedorA, "2026-05-20 09:30:00"));
        // Mismo numero de costo, pero paso de pesos a dolares: el costo cambio.
        cargar("CAL-4", "costoal cambio de moneda");
        historial(a, "CAL-4", "100.00", "100.00", "USD", "ARS", "2026-02-15 10:00:00");
        // Historial e importacion de otra ferreteria que apuntan a este articulo: no cuentan.
        cargar("CAL-5", "costoal datos ajenos");
        historial(b, "CAL-5", "100.00", "999.00", "ARS", "ARS", "2026-06-01 10:00:00");
        marcarImportacion("CAL-5", importacion(b, usuarioB, proveedorB, "2026-06-01 10:00:00"));
    }

    @AfterEach
    void limpiar() {
        // Primero lo cruzado a proposito entre las dos ferreterias, que el borrado por ferreteria no alcanza.
        jdbc.update("delete h from historial_precio h join articulo x on x.id = h.articulo_id"
                + " where x.ferreteria_id in (?, ?)", a, b);
        jdbc.update("update articulo set ultima_importacion_id = null where ferreteria_id in (?, ?)", a, b);
        borrarCotizaciones("cal-");
        borrarDatosDe(a, b);
    }

    private Long idDeUsuario(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private void cargar(String codigo, String descripcion) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad,"
                + " alicuota_iva, stock_actual, activo, creado_en) values (?, ?, ?, 100.00, 160.00, 'UNIDAD', 'IVA_21',"
                + " 0, true, ?)", a, codigo, descripcion, comoLaGuardaHibernate("2026-01-10 08:00:00"));
        ids.put(codigo, jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, a, codigo));
    }

    private void historial(Long ferreteriaId, String codigo, String costoAnterior, String costoNuevo, String moneda,
                           String monedaAnterior, String fecha) {
        jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_anterior, costo_nuevo,"
                + " precio_anterior, precio_nuevo, origen, moneda, moneda_anterior, fecha) values (?, ?, ?, ?, 160.00,"
                + " 170.00, 'MANUAL', ?, ?, ?)", ferreteriaId, ids.get(codigo), bd(costoAnterior), bd(costoNuevo),
                moneda, monedaAnterior, comoLaGuardaHibernate(fecha));
    }

    private Long importacion(Long ferreteriaId, Long usuarioId, Long proveedorId, String fecha) {
        String archivo = "lista-" + fecha + ".csv";
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan,"
                + " nuevos, sin_cambio, no_estan, con_error, estado, fecha) values (?, ?, ?, ?, 0, 0, 0, 1, 0, 0,"
                + " 'APLICADA', ?)", ferreteriaId, proveedorId, usuarioId, archivo, comoLaGuardaHibernate(fecha));
        return jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ? and archivo = ?",
                Long.class, ferreteriaId, archivo);
    }

    private void marcarImportacion(String codigo, Long importacionId) {
        jdbc.update("update articulo set ultima_importacion_id = ? where id = ?", importacionId, ids.get(codigo));
    }

    private Map<String, String> costoAlDelListado(String token) throws Exception {
        Map<String, String> fechas = new HashMap<>();
        getJson("/api/articulos?q=costoal&size=50", token).get("items")
                .forEach(i -> fechas.put(i.get("codigoInterno").asText(), i.get("costoAl").asText()));
        return fechas;
    }

    @Test
    @DisplayName("el listado trae la fecha mas nueva entre cambio de costo o moneda, importacion y alta, para Dueño y Vendedor")
    void listado() throws Exception {
        Map<String, String> esperadas = Map.of(
                "CAL-1", "2026-01-10",
                "CAL-2", "2026-03-05",
                "CAL-3", "2026-05-20",
                "CAL-4", "2026-02-15",
                "CAL-5", "2026-01-10");

        assertThat(costoAlDelListado(duenoA)).isEqualTo(esperadas);
        assertThat(costoAlDelListado(vendedorA)).isEqualTo(esperadas);
    }

    @Test
    @DisplayName("la ficha y la busqueda del mostrador traen la misma fecha")
    void fichaYMostrador() throws Exception {
        assertThat(getJson("/api/articulos/" + ids.get("CAL-3"), duenoA).get("costoAl").asText()).isEqualTo("2026-05-20");
        assertThat(getJson("/api/articulos/" + ids.get("CAL-3"), vendedorA).get("costoAl").asText())
                .isEqualTo("2026-05-20");

        JsonNode mostrador = getJson("/api/articulos/mostrador?q=costoal", vendedorA).get("items");
        Map<String, String> fechas = new HashMap<>();
        mostrador.forEach(i -> fechas.put(i.get("codigoInterno").asText(), i.get("costoAl").asText()));
        assertThat(fechas).containsEntry("CAL-2", "2026-03-05").containsEntry("CAL-3", "2026-05-20")
                .containsEntry("CAL-5", "2026-01-10");

        JsonNode exacto = getJson("/api/articulos/mostrador?q=CAL-4", vendedorA);
        assertThat(exacto.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(exacto.get("items").get(0).get("costoAl").asText()).isEqualTo("2026-02-15");
    }

    @Test
    @DisplayName("al cambiar el costo, la respuesta del guardado y el listado dicen hoy; cambiar solo la descripcion no")
    void guardar() throws Exception {
        Long id = ids.get("CAL-2");
        String sinCambioDeCosto = conLaVersionVigente(id, json("costoal cambio de costo bis", "100.00", "ARS"));
        JsonNode soloDescripcion = objectMapper.readTree(putJson("/api/articulos/" + id, duenoA, sinCambioDeCosto)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(soloDescripcion.get("costoAl").asText()).isEqualTo("2026-03-05");

        String conCambioDeCosto = conLaVersionVigente(id, json("costoal cambio de costo bis", "130.00", "ARS"));
        JsonNode nuevoCosto = objectMapper.readTree(putJson("/api/articulos/" + id, duenoA, conCambioDeCosto)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(nuevoCosto.get("costoAl").asText()).isEqualTo(HoraArgentina.hoy().toString());
        assertThat(costoAlDelListado(duenoA)).containsEntry("CAL-2", HoraArgentina.hoy().toString());
    }

    @Test
    @DisplayName("pasar a dolares con el mismo numero de costo es un cambio de costo: la fecha pasa a hoy")
    void cambioDeMonedaManual() throws Exception {
        Instant haceUnRato = Instant.parse("2026-10-16T11:00:00Z");
        cotizacion("USD", "cal-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> {
            c.setCasaPrecios("cal-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "cal-oficial", LadoCotizacion.VENTA, haceUnRato);
        });
        Long id = ids.get("CAL-2");

        String enDolares = conLaVersionVigente(id, json("costoal cambio de costo", "100.00", "USD"));
        JsonNode guardado = objectMapper.readTree(putJson("/api/articulos/" + id, duenoA, enDolares)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(guardado.get("moneda").asText()).isEqualTo("USD");
        assertThat(guardado.get("costoAl").asText()).isEqualTo(HoraArgentina.hoy().toString());
        assertThat(costoAlDelListado(duenoA)).containsEntry("CAL-2", HoraArgentina.hoy().toString());
    }

    @Test
    @DisplayName("la fecha es el dia de Buenos Aires en que se guardo, tambien a la noche y a la madrugada")
    void nocheYMadrugada() throws Exception {
        jdbc.update("update articulo set creado_en = ? where id = ?", comoLaGuardaHibernate("2026-01-10 22:30:00"), ids.get("CAL-1"));
        historial(a, "CAL-2", "120.00", "125.00", "ARS", "ARS", "2026-03-06 23:50:00");
        historial(a, "CAL-4", "100.00", "105.00", "USD", "USD", "2026-02-20 00:10:00");

        assertThat(costoAlDelListado(duenoA)).containsEntry("CAL-1", "2026-01-10").containsEntry("CAL-2", "2026-03-06")
                .containsEntry("CAL-4", "2026-02-20");
    }

    /**
     * Una hora de Buenos Aires guardada como la guarda Hibernate ({@code hibernate.jdbc.time_zone: UTC}): pasada a UTC con
     * la zona de la JVM (docs/spec.md, «Despliegue: zona horaria»). Un texto crudo en el SQL no pasa por esa conversion.
     */
    private static String comoLaGuardaHibernate(String fechaHora) {
        return LocalDateTime.parse(fechaHora.replace(' ', 'T')).atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ');
    }

    @Test
    @DisplayName("un alta nueva dice hoy")
    void alta() throws Exception {
        JsonNode creado = objectMapper.readTree(postJson("/api/articulos", duenoA, """
                {"codigoInterno": "CAL-9", "descripcion": "costoal alta nueva", "costo": 50, "moneda": "ARS",
                 "alicuotaIva": "IVA_21", "unidad": "UNIDAD", "stockMinimo": 0, "activo": true}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(creado.get("costoAl").asText()).isEqualTo(HoraArgentina.hoy().toString());
    }

    private static String json(String descripcion, String costo, String moneda) {
        return """
                {"codigoInterno": "CAL-2", "descripcion": "%s", "costo": %s, "moneda": "%s",
                 "alicuotaIva": "IVA_21", "unidad": "UNIDAD", "stockMinimo": 0, "activo": true}
                """.formatted(descripcion, costo, moneda);
    }
}
