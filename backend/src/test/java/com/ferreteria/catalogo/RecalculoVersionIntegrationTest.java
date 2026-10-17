package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.catalogo.service.RecalculoConReintento;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * El recalculo con reintento acotado contra MySQL (lote 5b, T-245, R-228). «Recalcular ahora» (HTTP) y el recalculo del
 * dia (el servicio, con {@code ahora} explicito) no fallan por chocar con la edicion de un articulo: reintentan hasta 3
 * veces, cada intento en su transaccion, y el articulo editado queda con la edicion y con el dolar nuevo. CUIT
 * 30-78401061-1; casa propia de cotizacion (t245-). El multiplo de redondeo es 10 y el margen 30, con IVA 21.
 */
class RecalculoVersionIntegrationTest extends VersionTestBase {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 16);

    @Autowired
    RecalculoConReintento recalculo;

    private Long f;
    private String dueno;
    private Long a;
    private Long b;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78401061-1", "Version recalculo");
        borrarDatosDe(f);
        // El dolar vigente es 1.000 y el ultimo guardado 1.100: recalcular lo pasa a 1.100.
        conDolar(f, "t245-oficial", "1000", "1100");
        dueno = login(usuario(f, "dueno-t245@test.local", "DUENO"));
        a = articuloUsd(f, "V245-A", "10.00", "15730.00");
        b = articuloUsd(f, "V245-B", "10.00", "15730.00");
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t245-");
        borrarDatosDe(f);
    }

    /** Lo que otro Dueno hace en el medio: el PUT REAL de A con costo 12,00 (12 x 1.000 x 1,573 = 18.876, sale $ 18.880). */
    private void editaA() throws Exception {
        putArticulo(dueno, a, cuerpoDe(dueno, a).put("costo", new BigDecimal("12.00"))).andExpect(status().isOk());
    }

    private <T> T comoF(Supplier<T> accion) {
        TenantContext.setCurrentFerreteriaId(f);
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
        }
    }

    private List<Object> origenes(Long id) {
        return historialDe(id).stream().map(h -> h.get("origen")).toList();
    }

    @Test
    @DisplayName("«Recalcular ahora» con una edicion de A en el medio: 200 con 2 articulos, A en $ 20.770 y B en $ 17.310")
    void recalcularAhoraReintenta() throws Exception {
        ganchoEnSaveAll(this::editaA);

        postJson("/api/cotizacion/recalculo", dueno, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cotizacion", is(1100.0)))
                .andExpect(jsonPath("$.articulosRecalculados", is(2)));

        assertThat(precioDe(a)).isEqualByComparingTo("20770");
        assertThat((BigDecimal) filaDe(a).get("costo")).isEqualByComparingTo("12.00");
        assertThat(precioDe(b)).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("el historial: A tiene una entrada MANUAL (15.730 a 18.880) y UNA COTIZACION (18.880 a 20.770); B una COTIZACION (el intento que choco no dejo nada)")
    void elIntentoQueChocoNoDejaHistorial() throws Exception {
        ganchoEnSaveAll(this::editaA);

        postJson("/api/cotizacion/recalculo", dueno, "{}").andExpect(status().isOk());

        assertThat(origenes(a)).containsExactly("MANUAL", "COTIZACION");
        Map<String, Object> manual = historialDe(a).get(0);
        assertThat((BigDecimal) manual.get("precio_anterior")).isEqualByComparingTo("15730");
        assertThat((BigDecimal) manual.get("precio_nuevo")).isEqualByComparingTo("18880");
        Map<String, Object> cotizacion = historialDe(a).get(1);
        assertThat((BigDecimal) cotizacion.get("precio_anterior")).isEqualByComparingTo("18880");
        assertThat((BigDecimal) cotizacion.get("precio_nuevo")).isEqualByComparingTo("20770");
        assertThat(origenes(b)).containsExactly("COTIZACION");
        assertThat(configuracionDe(f).getVigente()).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("el recalculo diario hace lo mismo: termina ese mismo minuto con los 2 recalculados y el dia queda reclamado una vez")
    void elRecalculoDelDiaReintenta() throws Exception {
        ganchoEnSaveAll(this::editaA);

        Optional<RecalculoCotizacionResponse> respuesta =
                comoF(() -> recalculo.recalcularDelDia(LocalDateTime.of(2026, 10, 16, 8, 0)));

        assertThat(respuesta).hasValueSatisfying(r -> assertThat(r.articulosRecalculados()).isEqualTo(2));
        assertThat(precioDe(a)).isEqualByComparingTo("20770");
        assertThat(precioDe(b)).isEqualByComparingTo("17310");
        assertThat(origenes(a)).containsExactly("MANUAL", "COTIZACION");
        assertThat(configuracionDe(f).getUltimoRecalculoDia()).isEqualTo(HOY);
        // Segunda llamada del dia: ya reclamado, no hace nada.
        assertThat(comoF(() -> recalculo.recalcularDelDia(LocalDateTime.of(2026, 10, 16, 8, 1)))).isEmpty();
        assertThat(origenes(b)).containsExactly("COTIZACION");
    }

    @Test
    @DisplayName("un gancho que choca siempre: «Recalcular ahora» da 409 con el mensaje de version y no cambia ningun precio ni deja historial")
    void tresChoquesDan409() throws Exception {
        ganchoSiempreEnSaveAll(() -> jdbc.update(
                "update articulo set descripcion = concat(descripcion, 'x'), version = version + 1 where id = ?", a));

        postJson("/api/cotizacion/recalculo", dueno, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)));

        assertThat(precioDe(a)).isEqualByComparingTo("15730");
        assertThat(precioDe(b)).isEqualByComparingTo("15730");
        assertThat(historialDeLaFerreteria(f)).isZero();
        assertThat(configuracionDe(f).getVigente()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("en el recalculo diario, 3 choques dejan el error afuera, el dia SIN reclamar y nada escrito: se reintenta al minuto siguiente")
    void tresChoquesEnElDiaSeReintentanAlMinuto() throws Exception {
        ganchoSiempreEnSaveAll(() -> jdbc.update(
                "update articulo set descripcion = concat(descripcion, 'x'), version = version + 1 where id = ?", a));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> comoF(() -> recalculo.recalcularDelDia(LocalDateTime.of(2026, 10, 16, 8, 0))))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(precioDe(a)).isEqualByComparingTo("15730");
        assertThat(historialDeLaFerreteria(f)).isZero();
        assertThat(configuracionDe(f).getUltimoRecalculoDia()).isNull();

        // Al minuto siguiente, sin el gancho, termina bien.
        soltarElGancho();
        assertThat(comoF(() -> recalculo.recalcularDelDia(LocalDateTime.of(2026, 10, 16, 8, 1)))).isPresent();
        assertThat(precioDe(a)).isEqualByComparingTo("17310");
        assertThat(configuracionDe(f).getUltimoRecalculoDia()).isEqualTo(HOY);
    }
}
