package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionRequest;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El dolar de los precios de cada ferreteria: activa, vigente y fijarUltima. Contra MySQL, con dos ferreterias propias
 * (CUIT 30-7450000x) y casas t18-* para no chocar con otros tests: la tabla cotizacion es global y se comparte.
 */
class DolarDePreciosServiceTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    @Autowired
    DolarDePrecios dolar;

    @Autowired
    ConfiguracionCotizacionService configuracionService;

    private Long a;
    private Long b;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-74500001-1", "Dolar A");
        b = ferreteria("30-74500002-2", "Dolar B");
        borrarCotizaciones("t18-");
        cotizacion("USD", "t18-oficial", "950", "1000", HACE_UN_RATO);
        cotizacion("USD", "t18-bolsa", "1040", "1050", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t18-oficial"));
        configuracion(b, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
        });
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t18-");
        borrarConfiguracion(a, b);
    }

    private <T> T como(Long ferreteriaId, java.util.function.Supplier<T> accion) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
        }
    }

    // ---- activa ----

    @Test
    @DisplayName("activa() refleja la configuracion de la ferreteria del TenantContext")
    void activaPorFerreteria() {
        configuracion(b, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setActiva(false);
        });

        assertThat(como(a, dolar::activa)).isTrue();
        assertThat(como(b, dolar::activa)).isFalse();
    }

    @Test
    @DisplayName("una ferreteria sin configuracion propia esta activada, y consultarlo no crea ninguna fila")
    void activaSinConfiguracion() {
        borrarConfiguracion(a);

        assertThat(como(a, dolar::activa)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from configuracion_cotizacion where ferreteria_id = ?",
                Integer.class, a)).isZero();
    }

    // ---- vigente ----

    @Test
    @DisplayName("sin vigente fijado usa el ultimo guardado de su tipo y su lado: venta para A, compra para B")
    void vigenteSinFijarUsaElUltimoDeSuTipo() {
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
        assertThat(como(b, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("950"));
    }

    @Test
    @DisplayName("si hay vigente guardado lo devuelve aunque el mercado ya se haya movido")
    void vigenteFijadoNoSigueAlMercado() {
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        cotizacion("USD", "t18-oficial", "1090", "1100", HACE_UN_RATO.plusSeconds(60));

        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
    }

    @Test
    @DisplayName("R-033: A con vigente 1000 y B con 950 ven cada una el suyo")
    void cadaFerreteriaVeSuVigente() {
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t18-oficial");
            c.fijarVigente(new BigDecimal("950.0000"), "t18-oficial", LadoCotizacion.COMPRA, HACE_UN_RATO);
        });

        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
        assertThat(como(b, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("950"));
    }

    @Test
    @DisplayName("nunca cae en otro tipo: si el tipo de la ferreteria no tiene valor guardado, no hay vigente")
    void vigenteNuncaCaeEnOtroTipo() {
        configuracion(a, c -> c.setCasaPrecios("t18-mep"));

        assertThat(como(a, dolar::vigente)).isEmpty();
    }

    @Test
    @DisplayName("si el tipo solo informa el otro lado, no hay vigente (no se usa el lado equivocado)")
    void vigenteSinElLadoElegido() {
        cotizacion("USD", "t18-solo-compra", "900", null, HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t18-solo-compra")); // lado VENTA, que ese tipo no informa

        assertThat(como(a, dolar::vigente)).isEmpty();
    }

    // ---- fijarUltima ----

    @Test
    @DisplayName("fijarUltima toma el ultimo guardado del dolar de precios, lo fija con casa, lado y fecha, y lo devuelve")
    void fijarUltima() {
        Instant antes = Instant.now();

        BigDecimal fijado = como(a, dolar::fijarUltima);

        assertThat(fijado).isEqualByComparingTo("1000");
        ConfiguracionCotizacion config = configuracionDe(a);
        assertThat(config.getVigente()).isEqualByComparingTo("1000");
        assertThat(config.getVigenteCasa()).isEqualTo("t18-oficial");
        assertThat(config.getVigenteLado()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(config.getVigenteDesde()).isBetween(antes.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("fijarUltima de B (oficial compra) fija 950 y deja intacta a A")
    void fijarUltimaPorFerreteria() {
        BigDecimal fijado = como(b, dolar::fijarUltima);

        assertThat(fijado).isEqualByComparingTo("950");
        assertThat(configuracionDe(b).getVigente()).isEqualByComparingTo("950");
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("fijarUltima con la cotizacion apagada lanza ConflictException y no fija nada")
    void fijarUltimaApagada() {
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setActiva(false);
        });

        assertThatThrownBy(() -> como(a, dolar::fijarUltima)).isInstanceOf(ConflictException.class)
                .hasMessage("La cotización está apagada");
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("fijarUltima sin ningun valor guardado lanza ConflictException con el aviso de esperar")
    void fijarUltimaSinValor() {
        configuracion(a, c -> c.setCasaPrecios("t18-mep"));

        assertThatThrownBy(() -> como(a, dolar::fijarUltima)).isInstanceOf(ConflictException.class)
                .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("un cambio de tipo en la configuracion no cambia vigente() hasta el proximo fijarUltima (R-028)")
    void cambioDeTipoSeAplicaAlProximoRecalculo() {
        como(a, dolar::fijarUltima);
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));

        como(a, () -> configuracionService.actualizar(new ConfiguracionCotizacionRequest(true, "USD:oficial",
                LadoCotizacion.COMPRA, List.of("USD:oficial"), "08:00")));
        // La config ahora apunta a USD:oficial (real, sin valor sembrado): el vigente fijado sigue siendo el de antes.
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
        assertThat(configuracionDe(a).getLadoPrecios()).isEqualTo(LadoCotizacion.COMPRA);

        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        assertThat(como(a, dolar::fijarUltima)).isEqualByComparingTo("950");
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("950"));
    }

    @Test
    @DisplayName("fijarUltima sin configuracion propia la crea con los valores por defecto antes de fijar")
    void fijarUltimaCreaLaConfiguracion() {
        borrarConfiguracion(a);
        cotizacion("USD", "oficial", "950", "1000", HACE_UN_RATO);
        try {
            BigDecimal fijado = como(a, dolar::fijarUltima);

            assertThat(fijado).isEqualByComparingTo("1000");
            assertThat(configuracionDe(a).getVigenteCasa()).isEqualTo("oficial");
        } finally {
            jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        }
    }
}
