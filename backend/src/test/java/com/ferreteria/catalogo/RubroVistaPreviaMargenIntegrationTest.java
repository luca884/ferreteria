package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vista previa del margen nuevo de un rubro (mejoras-rubros, S1): antes de confirmar el cambio, el Dueño ve la lista de
 * los articulos que cambian de precio, con el precio de hoy y el nuevo. No guarda nada. Ferreterias propias (CUIT
 * 30-77150001-1 y 30-77150002-2), margen por defecto 30 y redondeo a multiplos de 10.
 */
class RubroVistaPreviaMargenIntegrationTest extends CotizacionTestBase {

    private Long a;
    private Long b;
    private Long rubro;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77150001-1", "Vista previa margen A");
        b = ferreteria("30-77150002-2", "Vista previa margen B");
        jdbc.update("update ferreteria set margen_defecto_porcentaje = 30.00, redondeo_multiplo = 10.00 where id in (?, ?)",
                a, b);
        dueno = login(usuario(a, "dueno-s15a@test.local", "DUENO"));
        rubro = rubroDe(a, "Buloneria s15");
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    private Long rubroDe(Long ferreteriaId, String nombre) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, 35.00)", ferreteriaId,
                nombre);
        return jdbc.queryForObject("select id from rubro where ferreteria_id = ? and nombre = ?", Long.class,
                ferreteriaId, nombre);
    }

    /** Articulo del rubro sin margen propio, ya calculado con el 35 % del rubro (IVA 21, multiplo 10). */
    private Long delRubro(Long ferreteriaId, Long rubroId, String codigo, String moneda, String costo, String precio) {
        Long id = articulo(ferreteriaId, codigo, moneda, costo, precio, true);
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubroId, id);
        return id;
    }

    private String vistaPrevia(Long rubroId, String margen) {
        return "/api/rubros/" + rubroId + "/vista-previa-margen?margenPorcentaje=" + margen;
    }

    private BigDecimal margenDelRubro() {
        return jdbc.queryForObject("select margen_porcentaje from rubro where id = ?", BigDecimal.class, rubro);
    }

    @Test
    @DisplayName("de 35 a 36 %: con 3 herederos (uno con precio manual y uno que el redondeo deja igual) trae 1 y dice 1, sin guardar nada")
    void traeSoloLosQueCambianDePrecio() throws Exception {
        // Costo 1000: 1640 con 35 % y 1650 con 36 %. Costo 100: 170 con los dos (el redondeo lo deja igual).
        Long cambia = delRubro(a, rubro, "S15-CAMBIA", "ARS", "1000.00", "1640.00");
        Long igual = delRubro(a, rubro, "S15-IGUAL", "ARS", "100.00", "170.00");
        Long manual = delRubro(a, rubro, "S15-MANUAL", "ARS", "1000.00", "1640.00");
        jdbc.update("update articulo set precio_manual = true where id = ?", manual);

        JsonNode vista = getJson(vistaPrevia(rubro, "36"), dueno);

        assertThat(vista.get("cantidad").asLong()).isEqualTo(1);
        assertThat(vista.get("recortado").asBoolean()).isFalse();
        assertThat(vista.get("articulos")).singleElement().satisfies(fila -> {
            assertThat(fila.get("id").asLong()).isEqualTo(cambia);
            assertThat(fila.get("codigo").asText()).isEqualTo("S15-CAMBIA");
            assertThat(fila.get("descripcion").asText()).isEqualTo("Articulo S15-CAMBIA");
            assertThat(fila.get("precioActual").decimalValue()).isEqualByComparingTo("1640");
            assertThat(fila.get("precioNuevo").decimalValue()).isEqualByComparingTo("1650");
        });
        // Es una vista previa: ni el rubro ni los precios cambian y no hay historial.
        assertThat(margenDelRubro()).isEqualByComparingTo("35");
        assertThat(precioDe(cambia)).isEqualByComparingTo("1640");
        assertThat(precioDe(igual)).isEqualByComparingTo("170");
        assertThat(historialDeLaFerreteria(a)).isZero();
    }

    @Test
    @DisplayName("solo entran los activos que heredan el margen: ni el que tiene margen propio ni el dado de baja")
    void soloEntranLosActivosSinMargenPropio() throws Exception {
        Long hereda = delRubro(a, rubro, "S15-HEREDA", "ARS", "1000.00", "1640.00");
        Long propio = delRubro(a, rubro, "S15-PROPIO", "ARS", "1000.00", "1820.00");
        jdbc.update("update articulo set margen_porcentaje = 50.00 where id = ?", propio);
        Long deBaja = delRubro(a, rubro, "S15-BAJA", "ARS", "1000.00", "1640.00");
        jdbc.update("update articulo set activo = false where id = ?", deBaja);

        JsonNode vista = getJson(vistaPrevia(rubro, "36"), dueno);

        assertThat(vista.get("cantidad").asLong()).isEqualTo(1);
        assertThat(vista.get("articulos")).singleElement()
                .satisfies(fila -> assertThat(fila.get("id").asLong()).isEqualTo(hereda));
    }

    @Test
    @DisplayName("un rubro sin articulos da cantidad 0 y la lista vacia (la pantalla guarda sin preguntar)")
    void unRubroSinArticulosDaCero() throws Exception {
        JsonNode vista = getJson(vistaPrevia(rubro, "36"), dueno);

        assertThat(vista.get("cantidad").asLong()).isZero();
        assertThat(vista.get("articulos")).isEmpty();
        assertThat(vista.get("recortado").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("un margen fuera de 0..1000 da 400 con el mensaje del campo, igual que al guardar el rubro")
    void unMargenFueraDeRangoDa400() throws Exception {
        getResult(vistaPrevia(rubro, "-1"), dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.margenPorcentaje", is("El margen no puede ser negativo.")));
        getResult(vistaPrevia(rubro, "1000.01"), dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.margenPorcentaje", is("El margen no puede superar 1000 %.")));
    }

    @Test
    @DisplayName("el Vendedor recibe 403: es informacion del margen, solo del Dueño (H07)")
    void elVendedorNoLaVe() throws Exception {
        String vendedor = login(usuario(a, "vendedor-s15a@test.local", "VENDEDOR"));
        delRubro(a, rubro, "S15-CAMBIA", "ARS", "1000.00", "1640.00");

        getResult(vistaPrevia(rubro, "36"), vendedor).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un rubro de otra ferreteria da 404, igual que uno que no existe, y no muestra sus articulos")
    void unRubroDeOtraFerreteriaDa404() throws Exception {
        Long rubroDeB = rubroDe(b, "Buloneria s15 B");
        delRubro(b, rubroDeB, "S15-AJENO", "ARS", "1000.00", "1640.00");

        getResult(vistaPrevia(rubroDeB, "36"), dueno)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")))
                .andExpect(jsonPath("$.articulos").doesNotExist());
    }

    @Test
    @DisplayName("con un heredero en USD y ninguna cotizacion da 409 con el mismo mensaje que el guardado")
    void sinCotizacionDa409() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("s15-no-existe"));
        delRubro(a, rubro, "S15-ARS", "ARS", "1000.00", "1640.00");
        delRubro(a, rubro, "S15-USD", "USD", "10.00", "16340.00");

        getResult(vistaPrevia(rubro, "50"), dueno)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));
    }
}
