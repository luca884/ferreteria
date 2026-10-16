package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * La cotizacion es global: no tiene ferreteria_id y su repositorio no es de tenant. La base se comparte y no se
 * revierte, asi que cada test usa casas con nombre unico y borra lo que creo.
 */
class CotizacionRepositoryTest extends IntegrationTestBase {

    private static final Instant ACTUALIZADA = Instant.parse("2026-10-16T11:00:00Z");
    private static final Instant OBTENIDA = Instant.parse("2026-10-16T11:05:00.123456Z");

    @Autowired
    CotizacionRepository repositorio;

    private final List<Long> creadas = new ArrayList<>();

    @AfterEach
    void limpiar() {
        repositorio.deleteAllById(creadas);
        creadas.clear();
    }

    private Cotizacion guardar(String moneda, String casa, String compra, String venta, Instant obtenida) {
        Cotizacion guardada = repositorio.saveAndFlush(new Cotizacion(moneda, casa, "Prueba " + casa,
                compra == null ? null : new BigDecimal(compra), venta == null ? null : new BigDecimal(venta),
                "DOLARAPI", ACTUALIZADA, obtenida));
        creadas.add(guardada.getId());
        return guardada;
    }

    @Test
    @DisplayName("se guarda y se lee por (moneda, casa) con todos sus campos")
    void guardarYLeerPorMonedaYCasa() {
        String casa = unico("t8-lectura");
        guardar("USD", casa, "950.0000", "1000.0000", OBTENIDA);

        Cotizacion leida = repositorio.findByMonedaAndCasa("USD", casa).orElseThrow();

        assertThat(leida.getMoneda()).isEqualTo("USD");
        assertThat(leida.getCasa()).isEqualTo(casa);
        assertThat(leida.getNombre()).isEqualTo("Prueba " + casa);
        assertThat(leida.getCompra()).isEqualByComparingTo("950");
        assertThat(leida.getVenta()).isEqualByComparingTo("1000");
        assertThat(leida.getFuente()).isEqualTo("DOLARAPI");
        assertThat(leida.getActualizadaEn()).isEqualTo(ACTUALIZADA);
        assertThat(leida.getObtenidaEn()).isEqualTo(OBTENIDA);
    }

    @Test
    @DisplayName("la misma casa en otra moneda es otra cotizacion, y una casa inexistente no se encuentra")
    void distintaMonedaMismaCasa() {
        String casa = unico("t8-moneda");
        guardar("USD", casa, "950", "1000", OBTENIDA);
        guardar("EUR", casa, "1100", "1150", OBTENIDA);

        assertThat(repositorio.findByMonedaAndCasa("EUR", casa).orElseThrow().getVenta()).isEqualByComparingTo("1150");
        assertThat(repositorio.findByMonedaAndCasa("USD", casa).orElseThrow().getVenta()).isEqualByComparingTo("1000");
        assertThat(repositorio.findByMonedaAndCasa("BRL", casa)).isEmpty();
    }

    @Test
    @DisplayName("(moneda, casa) es unico: guardar dos veces el mismo tipo falla")
    void unicoPorMonedaYCasa() {
        String casa = unico("t8-unica");
        guardar("USD", casa, "950", "1000", OBTENIDA);

        assertThatThrownBy(() -> repositorio.saveAndFlush(new Cotizacion("USD", casa, "Duplicada",
                new BigDecimal("1"), new BigDecimal("2"), "BLUELYTICS", ACTUALIZADA, OBTENIDA)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("los cuatro decimales se guardan sin perdida: 1000.5000 y 1234.5678")
    void cuatroDecimalesSinPerdida() {
        String casa = unico("t8-decimales");
        guardar("USD", casa, "1000.5000", "1234.5678", OBTENIDA);

        Cotizacion leida = repositorio.findByMonedaAndCasa("USD", casa).orElseThrow();

        assertThat(leida.getCompra()).isEqualTo(new BigDecimal("1000.5000"));
        assertThat(leida.getVenta()).isEqualTo(new BigDecimal("1234.5678"));
    }

    @Test
    @DisplayName("compra o venta pueden faltar (hay tipos que informan un solo lado)")
    void unSoloLado() {
        String casa = unico("t8-lado");
        guardar("USD", casa, null, "1500.0000", OBTENIDA);

        Cotizacion leida = repositorio.findByMonedaAndCasa("USD", casa).orElseThrow();

        assertThat(leida.getCompra()).isNull();
        assertThat(leida.getVenta()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("actualizar cambia los valores del mismo registro y conserva el id")
    void actualizarConservaElRegistro() {
        String casa = unico("t8-actualizar");
        Cotizacion original = guardar("USD", casa, "950", "1000", OBTENIDA);
        Instant nueva = Instant.parse("2026-10-16T12:00:00Z");

        Cotizacion leida = repositorio.findByMonedaAndCasa("USD", casa).orElseThrow();
        leida.actualizar("Nuevo nombre", new BigDecimal("960.0000"), new BigDecimal("1010.0000"), "BLUELYTICS",
                Instant.parse("2026-10-16T11:55:00Z"), nueva);
        repositorio.saveAndFlush(leida);

        Cotizacion releida = repositorio.findByMonedaAndCasa("USD", casa).orElseThrow();
        assertThat(releida.getId()).isEqualTo(original.getId());
        assertThat(releida.getNombre()).isEqualTo("Nuevo nombre");
        assertThat(releida.getVenta()).isEqualByComparingTo("1010");
        assertThat(releida.getFuente()).isEqualTo("BLUELYTICS");
        assertThat(releida.getObtenidaEn()).isEqualTo(nueva);
    }

    @Test
    @DisplayName("la ultima respuesta exitosa es el MAX(obtenida_en) de toda la tabla")
    void ultimaRespuesta() {
        Instant lejana = Instant.parse("2099-01-01T00:00:00Z");
        guardar("USD", unico("t8-max-a"), "1", "2", OBTENIDA);
        guardar("USD", unico("t8-max-b"), "1", "2", lejana);
        guardar("USD", unico("t8-max-c"), "1", "2", Instant.parse("2098-12-31T23:59:59Z"));

        assertThat(repositorio.ultimaObtenidaEn()).contains(lejana);
    }

    @Test
    @DisplayName("findByMoneda trae todos los tipos de esa moneda")
    void porMoneda() {
        String uno = unico("t8-mon-a");
        String dos = unico("t8-mon-b");
        guardar("BRL", uno, "1", "2", OBTENIDA);
        guardar("BRL", dos, "3", "4", OBTENIDA);
        guardar("USD", unico("t8-mon-c"), "5", "6", OBTENIDA);

        assertThat(repositorio.findByMoneda("BRL")).extracting(Cotizacion::getCasa).contains(uno, dos);
        assertThat(repositorio.findByMoneda("BRL")).extracting(Cotizacion::getMoneda).containsOnly("BRL");
    }
}
