package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El PUT del artículo con versión (lote 5b, T-241) por HTTP contra MySQL: dos Dueños con el mismo formulario, el
 * formulario abierto contra una importación, el 400 de una versión que no es un número, el orden de los 409 y el gancho
 * de choque dentro del propio guardado. CUIT 30-78401021-1 (A) y 30-78401022-2 (B); casa propia de cotización (t241-).
 */
class ArticuloVersionIntegrationTest extends VersionTestBase {

    private Long a;
    private Long b;
    private Long proveedorA;
    private String duenoA;
    private String segundoDuenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-78401021-1", "Version PUT A");
        b = ferreteria("30-78401022-2", "Version PUT B");
        borrarDatosDe(a, b);
        conDolar(a, "t241-oficial", "1000", "1100");
        conDolar(b, "t241-oficial", "1000", "1100");
        proveedorA = proveedor(a, "Proveedor A");
        duenoA = login(usuario(a, "dueno-t241a@test.local", "DUENO"));
        segundoDuenoA = login(usuario(a, "dueno2-t241a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t241a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t241b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t241-");
        borrarDatosDe(a, b);
    }

    private Long conVersion(Long ferreteria, String codigo, String costo, String precio, long version) {
        Long id = manual(ferreteria, codigo, costo, precio);
        jdbc.update("update articulo set version = ? where id = ?", version, id);
        return id;
    }

    private long manuales(Long id) {
        return jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ? and origen = 'MANUAL'",
                Long.class, id);
    }

    // ---- dos Dueños con el mismo formulario ----

    @Test
    @DisplayName("el primero guarda el costo con la version 5 (200, version 6) y el segundo, con la misma, recibe 409 sin escribir nada")
    void dosDuenosConElMismoFormulario() throws Exception {
        Long id = conVersion(a, "V241-1", "900.00", "1420.00", 5);
        ObjectNode delPrimero = cuerpoDe(duenoA, id).put("costo", new BigDecimal("950.00"))
                .put("descripcion", "Del primero").put("version", 5);
        ObjectNode delSegundo = cuerpoDe(segundoDuenoA, id).put("descripcion", "Del segundo").put("version", 5);

        putArticulo(duenoA, id, delPrimero).andExpect(status().isOk())
                .andExpect(jsonPath("$.precioFinal", is(1500.0)))
                .andExpect(jsonPath("$.version", is(6)));
        putArticulo(segundoDuenoA, id, delSegundo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)))
                .andExpect(jsonPath("$.errors", nullValue()));

        JsonNode articulo = articuloJson(duenoA, id);
        assertThat(articulo.get("costo").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(articulo.get("descripcion").asText()).isEqualTo("Del primero");
        assertThat(articulo.get("version").asLong()).isEqualTo(6);
        assertThat(manuales(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("la version que devuelve el PUT sirve para el guardado siguiente (200 y 7)")
    void laVersionDeLaRespuestaSirveParaElProximoGuardado() throws Exception {
        Long id = conVersion(a, "V241-2", "900.00", "1420.00", 5);
        ObjectNode primero = cuerpoDe(duenoA, id).put("descripcion", "Uno").put("version", 5);
        long nueva = json(putArticulo(duenoA, id, primero).andExpect(status().isOk())).get("version").asLong();

        putArticulo(duenoA, id, cuerpoDe(duenoA, id).put("descripcion", "Dos").put("version", nueva))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(7)));
    }

    @Test
    @DisplayName("formulario abierto contra una importacion: la importacion sube el costo a 990 y el PUT con la version vieja da 409")
    void formularioAbiertoContraUnaImportacion() throws Exception {
        Long id = deLista(a, "V241-IMP", proveedorA);
        jdbc.update("update articulo set version = 5 where id = ?", id);
        ObjectNode formulario = cuerpoDe(duenoA, id).put("costo", new BigDecimal("900.00")).put("version", 5);

        aplicarImportacion(duenoA, xlsx(lista(fila("V241-IMP", "Lista", "1100"))),
                datosDeImportacion(proveedorA, "10"), false).andExpect(status().isCreated());
        putArticulo(duenoA, id, formulario).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)));

        assertThat(articuloJson(duenoA, id).get("costo").decimalValue()).isEqualByComparingTo("990.00");
        assertThat(versionDe(id)).isEqualTo(6);
    }

    // ---- el cuerpo ----

    @Test
    @DisplayName("una version que no es un numero entero da 400")
    void unaVersionQueNoEsUnNumeroDa400() throws Exception {
        Long id = conVersion(a, "V241-ABC", "900.00", "1420.00", 5);
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("version", "abc");

        putArticulo(duenoA, id, cuerpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
        assertThat(versionDe(id)).isEqualTo(5);
    }

    // ---- T-250: la version es obligatoria (decision 48) ----

    private static final String FALTA_VERSION = "Falta la versión del artículo. Recargá y volvé a intentar.";

    @Test
    @DisplayName("un PUT del articulo sin version da 400 solo con el mensaje y la descripcion no cambia")
    void putSinVersionDa400() throws Exception {
        Long id = conVersion(a, "V250-SIN", "900.00", "1420.00", 5);
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("descripcion", "Sin version");
        cuerpo.remove("version");

        putArticulo(duenoA, id, cuerpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(FALTA_VERSION)))
                .andExpect(jsonPath("$.errors", nullValue()));

        assertThat(articuloJson(duenoA, id).get("descripcion").asText()).isNotEqualTo("Sin version");
        assertThat(versionDe(id)).isEqualTo(5);
        assertThat(manuales(id)).isZero();
    }

    @Test
    @DisplayName("un PUT con version null explicita da el mismo 400")
    void putConVersionNullDa400() throws Exception {
        Long id = conVersion(a, "V250-NUL", "900.00", "1420.00", 5);
        ObjectNode cuerpo = cuerpoDe(duenoA, id).putNull("version");

        putArticulo(duenoA, id, cuerpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(FALTA_VERSION)));
    }

    @Test
    @DisplayName("un PUT de ajustes sin version da el mismo 400 y los ajustes no cambian")
    void ajustesSinVersionDa400() throws Exception {
        Long id = deLista(a, "V250-AJ", proveedorA);

        putAjustes(duenoA, id, "{\"ajuste1\":-5}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(FALTA_VERSION)))
                .andExpect(jsonPath("$.errors", nullValue()));

        assertThat(articuloJson(duenoA, id).get("ajuste1").isNull()).isTrue();
        assertThat(versionDe(id)).isZero();
    }

    @Test
    @DisplayName("un articulo ajeno sin version da 404, no el 400 (PUT y PUT de ajustes)")
    void ajenoSinVersionDa404() throws Exception {
        Long deB = conVersion(b, "V250-B", "900.00", "1420.00", 5);
        ObjectNode cuerpo = cuerpoDe(duenoB, deB);
        cuerpo.remove("version");

        putArticulo(duenoA, deB, cuerpo).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        putAjustes(duenoA, deB, "{\"ajuste1\":-5}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));

        assertThat(versionDe(deB)).isEqualTo(5);
    }

    @Test
    @DisplayName("un Vendedor sin version da 403 (PUT y PUT de ajustes)")
    void vendedorSinVersionDa403() throws Exception {
        Long id = deLista(a, "V250-VEN", proveedorA);
        ObjectNode cuerpo = cuerpoDe(duenoA, id);
        cuerpo.remove("version");

        putArticulo(vendedorA, id, cuerpo).andExpect(status().isForbidden());
        putAjustes(vendedorA, id, "{\"ajuste1\":-5}").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("el orden es 404, 400 de la version faltante y 409: sin version y con un codigo repetido gana el 400")
    void sinVersionVaAntesQueLosCodigos() throws Exception {
        Long id = conVersion(a, "V250-COD", "900.00", "1420.00", 5);
        manual(a, "V250-OTRO", "100.00", "160.00");
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("codigoInterno", "V250-OTRO");
        cuerpo.remove("version");

        putArticulo(duenoA, id, cuerpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(FALTA_VERSION)));
    }

    @Test
    @DisplayName("un ajuste invalido sin version da el 400 de los ajustes (se valida primero) y con la version vieja y un ajuste valido, el 409")
    void ajustesSinVersionConUnAjusteInvalidoYConVersionVieja() throws Exception {
        Long id = deLista(a, "V250-ORD", proveedorA);

        putAjustes(duenoA, id, "{\"ajuste1\":-100}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
        putAjustes(duenoA, id, "{\"ajuste1\":-5,\"version\":7}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)));
    }

    // ---- orden de los 409 y de los roles ----

    @Test
    @DisplayName("una version vieja con un codigo interno repetido da el 409 de la version, no el de los codigos")
    void laVersionVaAntesQueLosCodigos() throws Exception {
        Long id = conVersion(a, "V241-COD", "900.00", "1420.00", 5);
        manual(a, "V241-OTRO", "100.00", "160.00");
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("codigoInterno", "V241-OTRO").put("version", 4);

        putArticulo(duenoA, id, cuerpo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)));
    }

    @Test
    @DisplayName("con la version correcta y un codigo interno repetido sigue el 409 de los codigos")
    void conLaVersionCorrectaSigueElDeLosCodigos() throws Exception {
        Long id = conVersion(a, "V241-COD2", "900.00", "1420.00", 5);
        manual(a, "V241-OTRO2", "100.00", "160.00");
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("codigoInterno", "V241-OTRO2").put("version", 5);

        putArticulo(duenoA, id, cuerpo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.codigoInterno").exists());
    }

    @Test
    @DisplayName("un articulo de otra ferreteria con una version vieja da 404 y un Vendedor da 403")
    void ajenoYVendedor() throws Exception {
        Long deB = conVersion(b, "V241-B", "900.00", "1420.00", 5);
        Long deA = conVersion(a, "V241-A", "900.00", "1420.00", 5);
        ObjectNode cuerpoB = cuerpoDe(duenoB, deB).put("version", 1);
        ObjectNode cuerpoA = cuerpoDe(duenoA, deA).put("version", 1);

        putArticulo(duenoA, deB, cuerpoB).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        putArticulo(vendedorA, deA, cuerpoA).andExpect(status().isForbidden());

        assertThat(versionDe(deB)).isEqualTo(5);
        assertThat(versionDe(deA)).isEqualTo(5);
    }

    // ---- el gancho: un cambio concurrente dentro del propio guardado ----

    @Test
    @DisplayName("gancho: la version es la correcta pero otro confirma entre leer y escribir: 409, sin historial y el articulo como lo dejo el otro")
    void unCambioConcurrenteDentroDelGuardado() throws Exception {
        Long id = conVersion(a, "V241-GAN", "900.00", "1420.00", 5);
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("costo", new BigDecimal("950.00")).put("version", 5);
        ganchoEnSave(() -> jdbc.update(
                "update articulo set descripcion = 'Del otro', version = version + 1 where id = ?", id));

        putArticulo(duenoA, id, cuerpo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)));

        JsonNode articulo = articuloJson(duenoA, id);
        assertThat(articulo.get("costo").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(articulo.get("descripcion").asText()).isEqualTo("Del otro");
        assertThat(articulo.get("version").asLong()).isEqualTo(6);
        assertThat(manuales(id)).isZero();
    }

    // ---- T-247: aislamiento de la version (R-230) ----

    @Test
    @DisplayName("el Dueno de A manda el PUT y el PUT de ajustes de un articulo de B con la version correcta: 404 y el articulo sigue igual")
    void laVersionCorrectaNoAbreUnArticuloAjeno() throws Exception {
        Long x = deLista(b, "V247-X", proveedor(b, "Proveedor B"));
        jdbc.update("update articulo set version = 2 where id = ?", x);
        ObjectNode cuerpo = cuerpoDe(duenoB, x).put("descripcion", "Robada").put("version", 2);
        var antes = filaDe(x);

        putArticulo(duenoA, x, cuerpo).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        putAjustes(duenoA, x, "{\"ajuste1\":-5,\"version\":2}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));

        assertThat(filaDe(x)).isEqualTo(antes);
        assertThat(versionDe(x)).isEqualTo(2);
    }

    @Test
    @DisplayName("la version es un contador por articulo: uno de A y otro de B editados dos veces cada uno tienen version 2")
    void laVersionEsUnContadorPorArticulo() throws Exception {
        Long deA = manual(a, "V247-A", "100.00", "160.00");
        Long deB = manual(b, "V247-B", "100.00", "160.00");

        for (int i = 1; i <= 2; i++) {
            putArticulo(duenoA, deA, cuerpoDe(duenoA, deA).put("descripcion", "A" + i)).andExpect(status().isOk());
            putArticulo(duenoB, deB, cuerpoDe(duenoB, deB).put("descripcion", "B" + i)).andExpect(status().isOk());
        }

        assertThat(articuloJson(duenoA, deA).get("version").asLong()).isEqualTo(2);
        assertThat(articuloJson(duenoB, deB).get("version").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("un ferreteriaId en el cuerpo del PUT se ignora: el articulo sigue en su ferreteria")
    void unFerreteriaIdEnElCuerpoSeIgnora() throws Exception {
        Long id = manual(a, "V247-F", "100.00", "160.00");
        ObjectNode cuerpo = cuerpoDe(duenoA, id).put("descripcion", "Con ferreteria").put("ferreteriaId", b)
                .put("version", 0);

        putArticulo(duenoA, id, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));

        assertThat(jdbc.queryForObject("select ferreteria_id from articulo where id = ?", Long.class, id)).isEqualTo(a);
    }

    @Test
    @DisplayName("un masivo y una importacion de A que dan 409 por un choque no tocan nada de B (ni su version)")
    void unChoqueDeAnoTocaB() throws Exception {
        Long proveedorB = proveedor(b, "Proveedor B");
        Long deB = deLista(b, "V247-IMPB", proveedorB);
        Long masivoA = deLista(a, "V247-MAS", proveedorA);
        Long importadoA = deLista(a, "V247-IMP", proveedorA);
        var antesB = filaDe(deB);
        long versionB = versionDe(deB);

        // Masivo de A (solo el articulo del proveedor A): otro cambio entra en el medio.
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedorA);
        JsonNode previa = previaMasivo(duenoA, pedido);
        ganchoEnSaveAll(() -> jdbc.update("update articulo set descripcion = 'Del otro', version = version + 1 "
                + "where id = ?", masivoA));
        aplicarMasivo(duenoA, pedido, previa).andExpect(status().isConflict());

        // Importacion de A: lo mismo con el articulo importado.
        soltarElGancho();
        ganchoEnSaveAll(() -> jdbc.update("update articulo set descripcion = 'Del otro', version = version + 1 "
                + "where id = ?", importadoA));
        aplicarImportacion(duenoA, xlsx(lista(fila("V247-IMP", "Lista", "1100"), fila("V247-MAS", "Lista", "1100"))),
                datosDeImportacion(proveedorA, "10"), false).andExpect(status().isConflict());

        assertThat(filaDe(deB)).isEqualTo(antesB);
        assertThat(versionDe(deB)).isEqualTo(versionB);
        assertThat(historialDelArticulo(deB)).isZero();
    }
}
