package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionRequest;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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
    DolarDePreciosService dolar;

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

        BigDecimal fijado = como(a, () -> dolar.fijarUltima());

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
        BigDecimal fijado = como(b, () -> dolar.fijarUltima());

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

        assertThatThrownBy(() -> como(a, () -> dolar.fijarUltima())).isInstanceOf(ConflictException.class)
                .hasMessage("La cotización está apagada");
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("fijarUltima sin ningun valor guardado lanza ConflictException con el aviso de esperar")
    void fijarUltimaSinValor() {
        configuracion(a, c -> c.setCasaPrecios("t18-mep"));

        assertThatThrownBy(() -> como(a, () -> dolar.fijarUltima())).isInstanceOf(ConflictException.class)
                .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("un cambio de tipo en la configuracion no cambia vigente() hasta el proximo fijarUltima (R-028)")
    void cambioDeTipoSeAplicaAlProximoRecalculo() {
        como(a, () -> dolar.fijarUltima());
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
        assertThat(como(a, () -> dolar.fijarUltima())).isEqualByComparingTo("950");
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("950"));
    }

    @Test
    @DisplayName("fijarUltima sin configuracion propia la crea con los valores por defecto antes de fijar")
    void fijarUltimaCreaLaConfiguracion() {
        borrarConfiguracion(a);
        cotizacion("USD", "oficial", "950", "1000", HACE_UN_RATO);
        try {
            BigDecimal fijado = como(a, () -> dolar.fijarUltima());

            assertThat(fijado).isEqualByComparingTo("1000");
            assertThat(configuracionDe(a).getVigenteCasa()).isEqualTo("oficial");
        } finally {
            jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        }
    }

    // ---- dolar a confirmar (auditoria H09) ----

    private static final LocalDateTime HOY_A_LAS_9 = LocalDateTime.of(2026, 10, 17, 9, 0);

    /** A con vigente 1000 (oficial venta) y el mercado que vino en 14,5: un 98,55 % menos. */
    private void aConVigente1000YElMercadoEn14Coma5() {
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        cotizacion("USD", "t18-oficial", "14", "14.5", HACE_UN_RATO.plusSeconds(60));
    }

    @Test
    @DisplayName("un dolar a mas del umbral del vigente queda a confirmar, con el aviso armado")
    void aConfirmar() {
        aConVigente1000YElMercadoEn14Coma5();

        assertThat(como(a, dolar::aConfirmar)).hasValueSatisfying(p -> {
            assertThat(p.vigente()).isEqualByComparingTo("1000");
            assertThat(p.nuevo()).isEqualByComparingTo("14.5");
            assertThat(p.mensaje()).startsWith("El dólar t18-oficial venta vino en $ 14,50, un 98,55 % distinto");
        });
        assertThat(como(a, dolar::vigente)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
    }

    @Test
    @DisplayName("dentro del umbral, sin vigente o con otro tipo elegido no hay nada que confirmar")
    void nadaQueConfirmar() {
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.fijarVigente(new BigDecimal("960.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        assertThat(como(a, dolar::aConfirmar)).isEmpty(); // 960 a 1000: 4,17 %

        assertThat(como(b, dolar::aConfirmar)).isEmpty(); // B nunca fijo un vigente

        configuracion(a, c -> {
            c.setCasaPrecios("t18-bolsa"); // el Dueno eligio otro dolar: el salto es suyo, no de la fuente
            c.fijarVigente(new BigDecimal("500.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        assertThat(como(a, dolar::aConfirmar)).isEmpty();
    }

    @Test
    @DisplayName("el umbral es el de cada ferreteria: 1000 a 1060 (6 %) queda a confirmar con 5 y se aplica solo con 10")
    void umbralDeCadaFerreteria() {
        cotizacion("USD", "t18-oficial", "1050", "1060", HACE_UN_RATO.plusSeconds(60));
        configuracion(a, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setUmbralDesvioPorcentaje(new BigDecimal("5"));
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setUmbralDesvioPorcentaje(new BigDecimal("10"));
            c.fijarVigente(new BigDecimal("1000.0000"), "t18-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });

        assertThat(como(a, dolar::aConfirmar)).isPresent();
        assertThat(como(a, () -> dolar.reclamarRecalculoDelDia(HOY_A_LAS_9))).isEmpty();
        assertThat(como(b, dolar::aConfirmar)).isEmpty();
        assertThat(como(b, () -> dolar.reclamarRecalculoDelDia(HOY_A_LAS_9)))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1060"));
    }

    @Test
    @DisplayName("Recalcular ahora sin confirmar un dolar sospechoso da 409 con el aviso y no fija nada")
    void fijarUltimaSospechosaSinConfirmar() {
        aConVigente1000YElMercadoEn14Coma5();

        assertThatThrownBy(() -> como(a, () -> dolar.fijarUltima())).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("El dólar t18-oficial venta vino en $ 14,50");
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("confirmado por el Dueno, el dolar sospechoso se fija y deja de estar a confirmar")
    void fijarUltimaSospechosaConfirmada() {
        aConVigente1000YElMercadoEn14Coma5();

        assertThat(como(a, () -> dolar.fijarUltima(true))).isEqualByComparingTo("14.5");
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("14.5");
        assertThat(como(a, dolar::aConfirmar)).isEmpty();
    }

    @Test
    @DisplayName("el recalculo diario no aplica un dolar sospechoso ni marca el dia: lo reintenta y espera al Dueno")
    void recalculoDiarioNoAplicaLaSospechosa() {
        aConVigente1000YElMercadoEn14Coma5();

        assertThat(como(a, () -> dolar.reclamarRecalculoDelDia(HOY_A_LAS_9))).isEmpty();
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();

        // Llega un valor normal (1050, dentro del umbral): se aplica solo y la sospechosa queda superada.
        cotizacion("USD", "t18-oficial", "1040", "1050", HACE_UN_RATO.plusSeconds(120));
        assertThat(como(a, () -> dolar.reclamarRecalculoDelDia(HOY_A_LAS_9)))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1050"));
        assertThat(como(a, dolar::aConfirmar)).isEmpty();
    }

    @Test
    @DisplayName("la sospechosa de A no frena a B, que tiene otro vigente y la acepta dentro de su umbral")
    void cadaFerreteriaConfirmaLaSuya() {
        aConVigente1000YElMercadoEn14Coma5();
        configuracion(b, c -> {
            c.setCasaPrecios("t18-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
            c.fijarVigente(new BigDecimal("13.5000"), "t18-oficial", LadoCotizacion.COMPRA, HACE_UN_RATO);
        });

        assertThat(como(a, dolar::aConfirmar)).isPresent();
        assertThat(como(b, dolar::aConfirmar)).isEmpty(); // 13,5 a 14: 3,7 %
        assertThat(como(b, () -> dolar.fijarUltima())).isEqualByComparingTo("14");
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
    }
}
