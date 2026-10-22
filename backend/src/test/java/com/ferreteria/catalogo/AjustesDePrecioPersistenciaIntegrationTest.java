package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.catalogo.model.AjustePrecio;
import com.ferreteria.catalogo.model.AjustesDePrecio;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.TipoAjustePrecio;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * articulo.precio_manual y articulo.ajustes_precio (V24, CU-14) contra MySQL real: la lista de ajustes ida y vuelta
 * por Hibernate, con su forma JSON. CUIT: bloque 30-7816xxxx.
 */
class AjustesDePrecioPersistenciaIntegrationTest extends IntegrationTestBase {

    private static final ContextoPrecio CTX = new ContextoPrecio(null, new BigDecimal("30"), BigDecimal.ZERO);

    @Autowired
    ArticuloRepository articuloRepository;

    @AfterEach
    void limpiarElTenant() {
        TenantContext.clear();
    }

    private Long ferreteriaId(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
            + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long articulo(Long ferreteriaId, String codigo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, margen_porcentaje, "
            + "precio_final) values (?, ?, 'Tornillo', 100.00, 35.00, 163.35)", ferreteriaId, codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
            ferreteriaId, codigo);
    }

    @Test
    @DisplayName("los ajustes se guardan como JSON en orden y se leen iguales")
    void idaYVueltaDeLosAjustes() throws Exception {
        Long f = ferreteriaId("30-78160001-1");
        Long id = articulo(f, "CU14-1");
        TenantContext.setCurrentFerreteriaId(f);
        AjustesDePrecio ajustes = new AjustesDePrecio(List.of(
            new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, new BigDecimal("10")),
            new AjustePrecio(null, TipoAjustePrecio.MONTO, new BigDecimal("-3.35"))));

        Articulo articulo = articuloRepository.findScoped(id).orElseThrow();
        articulo.fijarAjustesDePrecio(ajustes, CTX);
        articuloRepository.saveAndFlush(articulo);

        Articulo leido = articuloRepository.findScoped(id).orElseThrow();
        assertThat(leido.getAjustesPrecio()).isEqualTo(ajustes);
        assertThat(leido.getPrecioFinal()).isEqualByComparingTo("176.34");
        assertThat(leido.isPrecioManual()).isFalse();
        JsonNode crudo = objectMapper.readTree(
            jdbc.queryForObject("select ajustes_precio from articulo where id = ?", String.class, id));
        assertThat(crudo.size()).isEqualTo(2);
        assertThat(crudo.get(0).get("nombre").asText()).isEqualTo("Flete");
        assertThat(crudo.get(0).get("tipo").asText()).isEqualTo("PORCENTAJE");
        assertThat(crudo.get(1).get("tipo").asText()).isEqualTo("MONTO");
        assertThat(crudo.get(1).get("valor").decimalValue()).isEqualByComparingTo("-3.35");
    }

    @Test
    @DisplayName("la marca de precio manual se guarda y el precio queda el escrito")
    void precioManual() {
        Long f = ferreteriaId("30-78160001-2");
        Long id = articulo(f, "CU14-2");
        TenantContext.setCurrentFerreteriaId(f);

        Articulo articulo = articuloRepository.findScoped(id).orElseThrow();
        articulo.fijarPrecioManual(new BigDecimal("1500"));
        articuloRepository.saveAndFlush(articulo);

        Articulo leido = articuloRepository.findScoped(id).orElseThrow();
        assertThat(leido.isPrecioManual()).isTrue();
        assertThat(leido.getPrecioFinal()).isEqualByComparingTo("1500");
        assertThat(leido.getAjustesPrecio().estaVacia()).isTrue();
    }

    @Test
    @DisplayName("leer y volver a guardar un articulo sin cambios no lo escribe (los ajustes se comparan por igualdad)")
    void sinCambiosNoSubeLaVersion() {
        Long f = ferreteriaId("30-78160001-3");
        Long id = articulo(f, "CU14-3");
        TenantContext.setCurrentFerreteriaId(f);
        Articulo articulo = articuloRepository.findScoped(id).orElseThrow();
        articulo.fijarAjustesDePrecio(new AjustesDePrecio(List.of(
            new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, new BigDecimal("10")))), CTX);
        articuloRepository.saveAndFlush(articulo);
        Long version = jdbc.queryForObject("select version from articulo where id = ?", Long.class, id);

        articuloRepository.saveAndFlush(articuloRepository.findScoped(id).orElseThrow());

        assertThat(jdbc.queryForObject("select version from articulo where id = ?", Long.class, id)).isEqualTo(version);
    }
}
