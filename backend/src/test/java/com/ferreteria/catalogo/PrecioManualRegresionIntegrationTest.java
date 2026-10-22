package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regresion de CU-14 contra MySQL real, pasada desde las sondas del verificador: la cascada del rubro y el masivo
 * respetan el precio manual y los ajustes, el aislamiento entre ferreterias de los endpoints nuevos, la deriva del
 * masivo y los bordes de las validaciones (S6). Cada test arma sus ferreterias propias (CUIT 30-7817xxxx-1, sin
 * redondeo, margen por defecto 30 %, IVA 21 %): costo 100 da 157,30.
 */
class PrecioManualRegresionIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final String FLETE_10 = "[{\"nombre\":\"Flete\",\"tipo\":\"PORCENTAJE\",\"valor\":10}]";

    private record Ferreteria(long id, String dueno, String vendedor) {}

    // ---- helpers ----

    private Ferreteria ferreteria() throws Exception {
        String cuit = String.format("30-7817%04d-1", SECUENCIA.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Regresion " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        String dueno = unico("dueno") + "@regresion.test";
        String vendedor = unico("vend") + "@regresion.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor', 'VENDEDOR')", id, vendedor, hash);
        return new Ferreteria(id, login(dueno), login(vendedor));
    }

    private long rubro(Ferreteria f, String margen) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, ?)",
                f.id(), unico("Rubro"), new BigDecimal(margen));
        return jdbc.queryForObject("select max(id) from rubro where ferreteria_id = ?", Long.class, f.id());
    }

    /** Articulo de costo 100, IVA 21, sin margen propio, con el precio, la marca y los ajustes que se indican. */
    private long articulo(Ferreteria f, Long rubroId, String precio, boolean manual, String ajustes) {
        String codigo = unico("RG");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, rubro_id, costo, "
                        + "alicuota_iva, precio_final, unidad, activo, precio_manual, ajustes_precio) "
                        + "values (?, ?, ?, ?, 100.00, 'IVA_21', ?, 'UNIDAD', true, ?, ?)",
                f.id(), codigo, "Articulo " + codigo, rubroId, new BigDecimal(precio), manual,
                ajustes == null ? "[]" : ajustes);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, f.id(), codigo);
    }

    private BigDecimal precio(long id) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, id);
    }

    private boolean manual(long id) {
        return jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, id);
    }

    private long historial(long id) {
        return jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ?", Long.class, id);
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode vistaPrevia(Ferreteria f, String modo, String valor) throws Exception {
        return cuerpo(postJson("/api/actualizacion-masiva/vista-previa", f.dueno(),
                "{\"modo\":\"" + modo + "\",\"valor\":" + valor + "}").andExpect(status().isOk()));
    }

    private ResultActions aplicar(Ferreteria f, String modo, String valor, String huella) throws Exception {
        return postJson("/api/actualizacion-masiva", f.dueno(),
                "{\"modo\":\"" + modo + "\",\"valor\":" + valor + ",\"huella\":\"" + huella + "\"}");
    }

    private ResultActions editarPrecioManual(Ferreteria f, long id, boolean manual) throws Exception {
        ObjectNode pedido = (ObjectNode) getJson("/api/articulos/" + id, f.dueno());
        pedido.put("precioManual", manual);
        return putJson("/api/articulos/" + id, f.dueno(), pedido.toString());
    }

    // ---- S1: cascada del rubro ----

    @Test
    @DisplayName("la cascada del rubro no cambia el precio manual ni deja historial, y aplica los ajustes al que los tiene")
    void cascadaDelRubroRespetaElManualYAplicaLosAjustes() throws Exception {
        Ferreteria f = ferreteria();
        long rubro = rubro(f, "30");
        long manual = articulo(f, rubro, "500.00", true, null);
        long conAjustes = articulo(f, rubro, "173.03", false, FLETE_10);
        long comun = articulo(f, rubro, "157.30", false, null);

        putJson("/api/rubros/" + rubro, f.dueno(), "{\"nombre\":\"Regresion\",\"margenPorcentaje\":50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avisos.length()", is(0)));

        assertThat(precio(manual)).isEqualByComparingTo("500.00");
        assertThat(manual(manual)).isTrue();
        assertThat(historial(manual)).isZero();
        // 100 x 1,50 x 1,21 = 181,50 y con el flete de 10 % = 199,65
        assertThat(precio(comun)).isEqualByComparingTo("181.50");
        assertThat(precio(conAjustes)).isEqualByComparingTo("199.65");
    }

    // ---- S4: actualizacion masiva ----

    @Test
    @DisplayName("el masivo por margen sobre un articulo con precio manual cambia el margen y deja el precio y la marca")
    void masivoPorMargenSobreUnPrecioManual() throws Exception {
        Ferreteria f = ferreteria();
        long manual = articulo(f, null, "500.00", true, null);

        JsonNode previa = vistaPrevia(f, "MARGEN", "50");
        aplicar(f, "MARGEN", "50", previa.get("huella").asText()).andExpect(status().isCreated());

        assertThat(precio(manual)).isEqualByComparingTo("500.00");
        assertThat(manual(manual)).isTrue();
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = ?", BigDecimal.class, manual))
                .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("el masivo de precio final sobre un articulo con ajustes los conserva; al destildar el manual vuelve al calculado con ellos")
    void masivoDePrecioFinalSobreAjustesYDestildar() throws Exception {
        Ferreteria f = ferreteria();
        long id = articulo(f, null, "173.03", false, FLETE_10);

        JsonNode previa = vistaPrevia(f, "PRECIO_PORCENTAJE", "-10");
        aplicar(f, "PRECIO_PORCENTAJE", "-10", previa.get("huella").asText()).andExpect(status().isCreated());

        // 173,03 - 10 % = 155,727 hacia arriba al centavo
        assertThat(precio(id)).isEqualByComparingTo("155.73");
        assertThat(manual(id)).isTrue();
        assertThat(jdbc.queryForObject("select ajustes_precio from articulo where id = ?", String.class, id))
                .contains("Flete");

        editarPrecioManual(f, id, false).andExpect(status().isOk());

        assertThat(manual(id)).isFalse();
        assertThat(precio(id)).isEqualByComparingTo("173.03");
    }

    @Test
    @DisplayName("tildar el precio manual entre la vista previa y aplicar del masivo da 409 y no cambia nada")
    void tildarElPrecioManualEntreLaVistaPreviaYAplicarDa409() throws Exception {
        Ferreteria f = ferreteria();
        long id = articulo(f, null, "157.30", false, null);
        JsonNode previa = vistaPrevia(f, "COSTO_PORCENTAJE", "10");

        editarPrecioManual(f, id, true).andExpect(status().isOk());
        aplicar(f, "COSTO_PORCENTAJE", "10", previa.get("huella").asText())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));

        assertThat(precio(id)).isEqualByComparingTo("157.30");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100.00");
    }

    // ---- S5: aislamiento ----

    @Test
    @DisplayName("el desglose y los ajustes de un articulo de otra ferreteria dan 404 y el masivo ajeno no lo toca")
    void aislamientoEntreFerreterias() throws Exception {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long ajeno = articulo(a, null, "500.00", true, FLETE_10);
        articulo(b, null, "160.00", false, null);

        getResult("/api/articulos/" + ajeno + "/desglose", b.dueno()).andExpect(status().isNotFound());
        getResult("/api/articulos/" + ajeno, b.dueno()).andExpect(status().isNotFound());
        putJson("/api/articulos/" + ajeno + "/ajustes", b.dueno(), "{\"ajuste1\":10}")
                .andExpect(status().isNotFound());

        JsonNode previa = vistaPrevia(b, "PRECIO_MONTO", "100");
        assertThat(previa.get("cambios")).hasSize(1);
        aplicar(b, "PRECIO_MONTO", "100", previa.get("huella").asText()).andExpect(status().isCreated());

        assertThat(precio(ajeno)).isEqualByComparingTo("500.00");
        assertThat(manual(ajeno)).isTrue();
        assertThat(jdbc.queryForObject("select ajustes_precio from articulo where id = ?", String.class, ajeno))
                .contains("Flete");
        getResult("/api/articulos/" + ajeno + "/desglose", a.vendedor()).andExpect(status().isForbidden());
    }

    // ---- S6: bordes de las validaciones ----

    /** Alta de un articulo de costo 100 y margen 30 (precio 157,30) con campos extra; devuelve el resultado. */
    private ResultActions alta(Ferreteria f, String extra) throws Exception {
        return postJson("/api/articulos", f.dueno(), "{\"codigoInterno\":\"" + unico("VAL")
                + "\",\"descripcion\":\"Borde\",\"unidad\":\"UNIDAD\",\"costo\":100,\"margenPorcentaje\":30"
                + extra + "}");
    }

    @Test
    @DisplayName("precio manual: el tope de DECIMAL(15,2) entra, el siguiente no, y mayor a 0 con 2 decimales")
    void bordesDelPrecioManual() throws Exception {
        Ferreteria f = ferreteria();

        alta(f, ",\"precioManual\":true,\"precioFinal\":9999999999999.99").andExpect(status().isCreated());
        alta(f, ",\"precioManual\":true,\"precioFinal\":10000000000000")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.precioFinal").exists());
        alta(f, ",\"precioManual\":true,\"precioFinal\":-1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.precioFinal").exists());
        // Con 3 decimales da 400: no se redondea sin avisar (S8). Ceros de mas no cuentan.
        alta(f, ",\"precioManual\":true,\"precioFinal\":10.555")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.precioFinal", is("El precio admite hasta 2 decimales.")));
        alta(f, ",\"precioManual\":true,\"precioFinal\":10.550").andExpect(status().isCreated())
                .andExpect(jsonPath("$.precioFinal", is(10.55)));
        // Sin la marca, el precio escrito se ignora y el articulo queda con el precio calculado.
        alta(f, ",\"precioManual\":false,\"precioFinal\":1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.precioManual", is(false)))
                .andExpect(jsonPath("$.precioFinal", is(157.3)));
    }

    @Test
    @DisplayName("ajuste en %: de -99,99 a 1000 entra; -100 y mas de 1000 dan 400 en el valor del ajuste")
    void bordesDelAjusteEnPorcentaje() throws Exception {
        Ferreteria f = ferreteria();

        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"PORCENTAJE\",\"valor\":-99.99}]").andExpect(status().isCreated());
        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"PORCENTAJE\",\"valor\":1000}]").andExpect(status().isCreated());
        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"PORCENTAJE\",\"valor\":-100}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['ajustesPrecio[0].valor']").exists());
        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"PORCENTAJE\",\"valor\":1000.01}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['ajustesPrecio[0].valor']").exists());
    }

    @Test
    @DisplayName("el nombre del ajuste tiene hasta 40 caracteres")
    void bordeDelNombreDelAjuste() throws Exception {
        Ferreteria f = ferreteria();

        alta(f, ",\"ajustesPrecio\":[{\"nombre\":\"" + "x".repeat(40) + "\",\"tipo\":\"MONTO\",\"valor\":1}]")
                .andExpect(status().isCreated());
        alta(f, ",\"ajustesPrecio\":[{\"nombre\":\"" + "x".repeat(41) + "\",\"tipo\":\"MONTO\",\"valor\":1}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['ajustesPrecio[0].nombre']").exists());
    }

    @Test
    @DisplayName("un ajuste en $ que deja el precio en 0 se rechaza a mano; a 1 centavo entra; con precio manual no pesa")
    void bordeDelAjusteQueDejaElPrecioEnCero() throws Exception {
        Ferreteria f = ferreteria();

        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"MONTO\",\"valor\":-157.30}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.ajustesPrecio").exists());
        alta(f, ",\"ajustesPrecio\":[{\"tipo\":\"MONTO\",\"valor\":-157.29}]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.precioFinal", is(0.01)));
        alta(f, ",\"precioManual\":true,\"precioFinal\":50,\"ajustesPrecio\":[{\"tipo\":\"MONTO\",\"valor\":-100000}]")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.precioFinal", is(50.0)));
    }
}
