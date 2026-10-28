package com.ferreteria.facturacion.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La CbteFch que entra al enviar (S5): no anterior a la del último autorizado, entre N−5 y N+5 del día del envío y, si es
 * posterior al envío, en el mismo mes. Lo que no entra pasa a FUERA_DE_FECHA.
 */
class VentanaCbteFchTest {

    @ParameterizedTest(name = "CbteFch {0}, envío {1}, último autorizado {2}: {3}")
    @CsvSource(nullValues = "-", value = {
        // el mismo día, sin último autorizado
        "2026-10-28, 2026-10-28, -,          true",
        // N-5 entra, N-6 no
        "2026-10-23, 2026-10-28, -,          true",
        "2026-10-22, 2026-10-28, -,          false",
        // N+5 en el mismo mes entra, N+6 no
        "2026-10-15, 2026-10-10, -,          true",
        "2026-10-16, 2026-10-10, -,          false",
        // posterior al envío y en otro mes: no entra, aunque esté dentro de N+5
        "2026-11-01, 2026-10-31, -,          false",
        // anterior al envío y en otro mes: entra (la regla del mes es solo para las posteriores)
        "2026-09-30, 2026-10-02, -,          true",
        // no anterior a la del último autorizado: igual entra, un día antes no
        "2026-10-27, 2026-10-28, 2026-10-27, true",
        "2026-10-26, 2026-10-28, 2026-10-27, false"})
    @DisplayName("la CbteFch entra solo dentro de la ventana")
    void ventana(LocalDate cbteFch, LocalDate hoy, LocalDate ultima, boolean entra) {
        assertThat(VentanaCbteFch.admite(cbteFch, hoy, ultima)).isEqualTo(entra);
    }
}
