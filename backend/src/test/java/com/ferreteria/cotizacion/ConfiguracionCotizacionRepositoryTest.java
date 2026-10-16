package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** La configuracion es de cada ferreteria: el repositorio es de tenant y solo se lee con los metodos *Scoped. */
class ConfiguracionCotizacionRepositoryTest extends IntegrationTestBase {

    @Autowired
    ConfiguracionCotizacionRepository repositorio;

    @AfterEach
    void limpiarTenant() {
        TenantContext.clear();
    }

    private Long ferreteria(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
                razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    @Test
    @DisplayName("por defecto: activa, oficial, VENTA, visibles USD:oficial, 08:00 y sin dolar vigente")
    void valoresPorDefecto() {
        ConfiguracionCotizacion config = ConfiguracionCotizacion.porDefecto(7L);

        assertThat(config.getFerreteriaId()).isEqualTo(7L);
        assertThat(config.isActiva()).isTrue();
        assertThat(config.getCasaPrecios()).isEqualTo("oficial");
        assertThat(config.getLadoPrecios()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(config.getVisibles()).containsExactly("USD:oficial");
        assertThat(config.getHoraRecalculo()).isEqualTo(LocalTime.of(8, 0));
        assertThat(config.getVigente()).isNull();
        assertThat(config.getVigenteCasa()).isNull();
        assertThat(config.getVigenteLado()).isNull();
        assertThat(config.getVigenteDesde()).isNull();
        assertThat(config.getUltimoRecalculoDia()).isNull();
    }

    @Test
    @DisplayName("la lista de visibles que se pide es una copia: cambiarla afuera no cambia la configuracion")
    void visiblesDefensivo() {
        ConfiguracionCotizacion config = ConfiguracionCotizacion.porDefecto(7L);

        assertThatThrownBy(() -> config.getVisibles().add("USD:blue")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(config.getVisibles()).containsExactly("USD:oficial");
    }

    @Test
    @DisplayName("se guarda y se relee con los visibles como JSON, el dolar de precios y la hora")
    void guardarYLeer() {
        Long f = ferreteria("30-73100001-1", "Config guardar");
        ConfiguracionCotizacion config = ConfiguracionCotizacion.porDefecto(f);
        config.setVisibles(List.of("USD:oficial", "USD:blue", "EUR:oficial"));
        config.setCasaPrecios("bolsa");
        config.setLadoPrecios(LadoCotizacion.COMPRA);
        config.setHoraRecalculo(LocalTime.of(9, 30));
        config.setActiva(false);

        TenantContext.setCurrentFerreteriaId(f);
        Long id = repositorio.saveAndFlush(config).getId();

        ConfiguracionCotizacion leida = repositorio.findScoped(id).orElseThrow();
        assertThat(leida.getVisibles()).containsExactly("USD:oficial", "USD:blue", "EUR:oficial");
        assertThat(leida.getCasaPrecios()).isEqualTo("bolsa");
        assertThat(leida.getLadoPrecios()).isEqualTo(LadoCotizacion.COMPRA);
        assertThat(leida.getHoraRecalculo()).isEqualTo(LocalTime.of(9, 30));
        assertThat(leida.isActiva()).isFalse();
        assertThat(jdbc.queryForObject("select lado_precios from configuracion_cotizacion where id = ?",
                String.class, id)).isEqualTo("COMPRA");
    }

    @Test
    @DisplayName("el dolar vigente y el ultimo dia de recalculo se guardan con 4 decimales")
    void dolarVigente() {
        Long f = ferreteria("30-73100002-2", "Config vigente");
        ConfiguracionCotizacion config = ConfiguracionCotizacion.porDefecto(f);
        Instant desde = Instant.parse("2026-10-16T11:00:00.500Z");
        config.fijarVigente(new BigDecimal("1000.5000"), "oficial", LadoCotizacion.VENTA, desde);
        config.setUltimoRecalculoDia(LocalDate.of(2026, 10, 16));

        TenantContext.setCurrentFerreteriaId(f);
        Long id = repositorio.saveAndFlush(config).getId();

        ConfiguracionCotizacion leida = repositorio.findScoped(id).orElseThrow();
        assertThat(leida.getVigente()).isEqualTo(new BigDecimal("1000.5000"));
        assertThat(leida.getVigenteCasa()).isEqualTo("oficial");
        assertThat(leida.getVigenteLado()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(leida.getVigenteDesde()).isEqualTo(desde);
        assertThat(leida.getUltimoRecalculoDia()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("findScoped con la ferreteria A no devuelve la configuracion de B")
    void aislamientoPorFerreteria() {
        Long a = ferreteria("30-73100003-3", "Config A");
        Long b = ferreteria("30-73100004-4", "Config B");
        TenantContext.setCurrentFerreteriaId(a);
        Long idA = repositorio.saveAndFlush(ConfiguracionCotizacion.porDefecto(a)).getId();
        TenantContext.setCurrentFerreteriaId(b);
        Long idB = repositorio.saveAndFlush(ConfiguracionCotizacion.porDefecto(b)).getId();

        TenantContext.setCurrentFerreteriaId(a);
        assertThat(repositorio.findScoped(idA)).isPresent();
        assertThat(repositorio.findScoped(idB)).isEmpty();
        assertThat(repositorio.findPropiaScoped().orElseThrow().getId()).isEqualTo(idA);
        assertThat(repositorio.findAllScoped()).extracting(ConfiguracionCotizacion::getFerreteriaId).containsOnly(a);

        TenantContext.setCurrentFerreteriaId(b);
        assertThat(repositorio.findScoped(idA)).isEmpty();
        assertThat(repositorio.findPropiaScoped().orElseThrow().getId()).isEqualTo(idB);
    }

    @Test
    @DisplayName("una ferreteria sin configuracion propia no encuentra ninguna")
    void sinConfiguracion() {
        Long f = ferreteria("30-73100005-5", "Config vacia");
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(repositorio.findPropiaScoped()).isEmpty();
        assertThat(repositorio.findAllScoped()).isEmpty();
    }

    @Test
    @DisplayName("hay una sola configuracion por ferreteria: la segunda falla por el UNIQUE")
    void unaPorFerreteria() {
        Long f = ferreteria("30-73100006-6", "Config unica");
        TenantContext.setCurrentFerreteriaId(f);
        repositorio.saveAndFlush(ConfiguracionCotizacion.porDefecto(f));

        assertThatThrownBy(() -> repositorio.saveAndFlush(ConfiguracionCotizacion.porDefecto(f)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
