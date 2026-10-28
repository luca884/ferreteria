package com.ferreteria.facturacion.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S1: los códigos de WSFEv1 (manual 4.8) que salen de los enums de {@code facturacion}, cada uno completo. */
class MapeoWsfeTest {

    private static <E extends Enum<E>, V> Map<E, V> mapa(E[] valores, Function<E, V> codigo) {
        return Arrays.stream(valores).collect(Collectors.toMap(Function.identity(), codigo));
    }

    @Test
    @DisplayName("CbteTipo por letra y clase: A 1 y 3, B 6 y 8, C 11 y 13")
    void cbteTipoPorLetraYClase() {
        assertThat(mapa(Letra.values(), l -> l.cbteTipo(ClaseComprobante.FACTURA)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(Letra.A, 1, Letra.B, 6, Letra.C, 11));
        assertThat(mapa(Letra.values(), l -> l.cbteTipo(ClaseComprobante.NOTA_CREDITO)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(Letra.A, 3, Letra.B, 8, Letra.C, 13));
    }

    @Test
    @DisplayName("DocTipo: CUIT 80, CUIL 86, DNI 96 y sin identificar 99")
    void docTipo() {
        assertThat(mapa(TipoDocumentoReceptor.values(), TipoDocumentoReceptor::docTipo))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        TipoDocumentoReceptor.CUIT, 80,
                        TipoDocumentoReceptor.CUIL, 86,
                        TipoDocumentoReceptor.DNI, 96,
                        TipoDocumentoReceptor.SIN_IDENTIFICAR, 99));
    }

    @Test
    @DisplayName("CondicionIVAReceptorId: RI 1, Exento 4, Consumidor Final 5 y Monotributista 6")
    void condicionIvaReceptorId() {
        assertThat(mapa(CondicionIvaReceptor.values(), CondicionIvaReceptor::condicionIvaReceptorId))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        CondicionIvaReceptor.RESPONSABLE_INSCRIPTO, 1,
                        CondicionIvaReceptor.EXENTO, 4,
                        CondicionIvaReceptor.CONSUMIDOR_FINAL, 5,
                        CondicionIvaReceptor.MONOTRIBUTISTA, 6));
    }

    @Test
    @DisplayName("alícuotas por Id de WSFEv1: 3 = 0 %, 4 = 10,5 %, 5 = 21 %, 6 = 27 %, 8 = 5 % y 9 = 2,5 %")
    void alicuotas() {
        assertThat(mapa(Alicuota.values(), Alicuota::id)).containsExactlyInAnyOrderEntriesOf(Map.of(
                Alicuota.IVA_0, 3, Alicuota.IVA_10_5, 4, Alicuota.IVA_21, 5,
                Alicuota.IVA_27, 6, Alicuota.IVA_5, 8, Alicuota.IVA_2_5, 9));

        Map<Alicuota, BigDecimal> porcentajes = mapa(Alicuota.values(), Alicuota::porcentaje);
        assertThat(porcentajes).hasSize(6);
        assertThat(porcentajes.get(Alicuota.IVA_0)).isEqualByComparingTo("0");
        assertThat(porcentajes.get(Alicuota.IVA_10_5)).isEqualByComparingTo("10.5");
        assertThat(porcentajes.get(Alicuota.IVA_21)).isEqualByComparingTo("21");
        assertThat(porcentajes.get(Alicuota.IVA_27)).isEqualByComparingTo("27");
        assertThat(porcentajes.get(Alicuota.IVA_5)).isEqualByComparingTo("5");
        assertThat(porcentajes.get(Alicuota.IVA_2_5)).isEqualByComparingTo("2.5");
    }
}
