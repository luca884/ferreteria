package com.ferreteria.facturacion.wsfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.model.Ambiente;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Los records del pedido llevan los campos con los largos del manual 4.8 (Int (n), Long (n), Double (e+d), String (n)): lo
 * que no entra no se puede armar. Los importes quedan con escala 2 y MonCotiz con escala 6, así un detalle leído de vuelta es
 * igual al enviado.
 */
class PedidoWsfeTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 28);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        PtoVta de 6 dígitos | PtoVta
        CbteTipo de 4 dígitos | CbteTipo
        CantReg distinto de 1 | CantReg
        DocNro de 12 dígitos | DocNro
        CbteDesde de 9 dígitos | CbteDesde
        CbteHasta distinto de CbteDesde | CbteHasta
        ImpTotal con 3 decimales | ImpTotal
        ImpNeto con 14 enteros | ImpNeto
        MonId de 4 letras | MonId
        MonCotiz con 7 decimales | MonCotiz
        AlicIva.Id de 3 dígitos | AlicIva.Id
        CbteAsoc.Nro de 9 dígitos | CbteAsoc.Nro
        CbteAsoc.Cuit de 10 caracteres | CbteAsoc.Cuit
        CondicionIVAReceptorId de 3 dígitos | CondicionIVAReceptorId
        Cuit de Auth de 12 dígitos | Cuit
        """)
    @DisplayName("un campo que no entra en su tipo de WSFEv1 no se puede armar, y el mensaje nombra el campo")
    void loQueNoEntraNoSeArma(String caso, String campo) {
        assertThatThrownBy(() -> armar(caso)).as(caso).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(campo);
    }

    @Test
    @DisplayName("los importes quedan con escala 2 y MonCotiz con escala 6: 121 y 121.00 dan el mismo detalle")
    void normalizaLasEscalas() {
        FeCaeDetRequest conEscalas = detalle(new BigDecimal("121"), new BigDecimal("1"));
        FeCaeDetRequest sinEscalas = detalle(new BigDecimal("121.00"), new BigDecimal("1.000000"));

        assertThat(conEscalas).isEqualTo(sinEscalas);
        assertThat(conEscalas.impTotal().scale()).isEqualTo(2);
        assertThat(conEscalas.monCotiz().scale()).isEqualTo(6);
        assertThat(new AlicIva(5, new BigDecimal("100"), new BigDecimal("21")).baseImp()).isEqualTo(new BigDecimal("100.00"));
        assertThat(conEscalas.iva()).isEmpty();
        assertThat(conEscalas.cbtesAsoc()).isEmpty();
    }

    private static FeCaeDetRequest detalle(BigDecimal impTotal, BigDecimal monCotiz) {
        return new FeCaeDetRequest(1, 99, 0, 1, 1, HOY, impTotal, BigDecimal.ZERO, impTotal, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "PES", monCotiz, 5, null, null, null);
    }

    private static Object armar(String caso) {
        BigDecimal cien = new BigDecimal("100");
        return switch (caso) {
            case "PtoVta de 6 dígitos" -> FeCabReq.de(100000, 6);
            case "CbteTipo de 4 dígitos" -> FeCabReq.de(1, 1000);
            case "CantReg distinto de 1" -> new FeCabReq(2, 1, 6);
            case "DocNro de 12 dígitos" -> detalleCon(99, 100_000_000_000L, 1, 1, cien, cien, "PES", BigDecimal.ONE, 5);
            case "CbteDesde de 9 dígitos" -> detalleCon(99, 0, 100_000_000L, 100_000_000L, cien, cien, "PES", BigDecimal.ONE, 5);
            case "CbteHasta distinto de CbteDesde" -> detalleCon(99, 0, 1, 2, cien, cien, "PES", BigDecimal.ONE, 5);
            case "ImpTotal con 3 decimales" -> detalleCon(99, 0, 1, 1, new BigDecimal("100.001"), cien, "PES",
                    BigDecimal.ONE, 5);
            case "ImpNeto con 14 enteros" -> detalleCon(99, 0, 1, 1, cien, new BigDecimal("10000000000000"), "PES",
                    BigDecimal.ONE, 5);
            case "MonId de 4 letras" -> detalleCon(99, 0, 1, 1, cien, cien, "PESO", BigDecimal.ONE, 5);
            case "MonCotiz con 7 decimales" -> detalleCon(99, 0, 1, 1, cien, cien, "PES", new BigDecimal("1.0000001"), 5);
            case "AlicIva.Id de 3 dígitos" -> new AlicIva(100, cien, cien);
            case "CbteAsoc.Nro de 9 dígitos" -> new CbteAsoc(6, 1, 100_000_000L, null, null);
            case "CbteAsoc.Cuit de 10 caracteres" -> new CbteAsoc(6, 1, 1, "3078601330", null);
            case "CondicionIVAReceptorId de 3 dígitos" -> detalleCon(99, 0, 1, 1, cien, cien, "PES", BigDecimal.ONE, 100);
            case "Cuit de Auth de 12 dígitos" -> new Auth(Ambiente.SIMULADOR, 100_000_000_000L);
            default -> throw new IllegalStateException(caso);
        };
    }

    private static FeCaeDetRequest detalleCon(int docTipo, long docNro, long desde, long hasta, BigDecimal impTotal,
            BigDecimal impNeto, String monId, BigDecimal monCotiz, int condicion) {
        return new FeCaeDetRequest(1, docTipo, docNro, desde, hasta, HOY, impTotal, BigDecimal.ZERO, impNeto,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, monId, monCotiz, condicion, List.of(), List.of(), null);
    }
}
