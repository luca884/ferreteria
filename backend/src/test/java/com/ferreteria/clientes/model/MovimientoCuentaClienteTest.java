package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-001, CC-004: un movimiento del libro de cuenta corriente es inmutable y una venta siempre es debe. Dominio puro. */
class MovimientoCuentaClienteTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 18, 11, 30);
    private static final String REF = "Venta N.º 5";

    @Test
    @DisplayName("una venta: tipo VENTA, debe = monto, haber = 0, sin autorización queda sin marca de exceso")
    void ventaSinExceso() {
        MovimientoCuentaCliente m = MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, REF, new BigDecimal("1000.50"), AHORA, 9L,
                null);

        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getClienteId()).isEqualTo(7L);
        assertThat(m.getComprobanteId()).isEqualTo(20L);
        assertThat(m.getPagoId()).isEqualTo(30L);
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoCuenta.VENTA);
        assertThat(m.getDebe()).isEqualByComparingTo("1000.50");
        assertThat(m.getHaber()).isEqualByComparingTo("0");
        assertThat(m.getFecha()).isEqualTo(AHORA);
        assertThat(m.getUsuarioId()).isEqualTo(9L);
        assertThat(m.isExcedioLimite()).isFalse();
        assertThat(m.getAutorizadoPor()).isNull();
    }

    @Test
    @DisplayName("con autorizadoPor, excedio_limite es true y guarda quién autorizó")
    void ventaConExcesoAutorizado() {
        MovimientoCuentaCliente m = MovimientoCuentaCliente.venta(3L, 7L, 20L, 31L, REF, new BigDecimal("250"), AHORA, 9L, 4L);

        assertThat(m.isExcedioLimite()).isTrue();
        assertThat(m.getAutorizadoPor()).isEqualTo(4L);
        assertThat(m.getDebe()).isEqualByComparingTo("250");
        assertThat(m.getHaber()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el monto tiene que ser mayor que cero")
    void montoInvalido() {
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, REF, BigDecimal.ZERO, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, REF, new BigDecimal("-1"), AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, REF, null, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la venta exige comprobante y pago (el CHECK de la base los pide)")
    void ventaExigeReferencias() {
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, null, 30L, REF, BigDecimal.TEN, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, null, REF, BigDecimal.TEN, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la venta guarda su referencia copiada, sin recibo; sin referencia o de más de 60 caracteres se rechaza")
    void ventaConReferencia() {
        MovimientoCuentaCliente m = MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, "Venta N.º 42", BigDecimal.TEN, AHORA,
                9L, null);

        assertThat(m.getReferencia()).isEqualTo("Venta N.º 42");
        assertThat(m.getReciboId()).isNull();
        assertThat(m.getDebe()).isEqualByComparingTo("10");
        // en el límite (60) entra; 61 y vacíos se rechazan
        assertThat(MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, "x".repeat(60), BigDecimal.TEN, AHORA, 9L, null)
                .getReferencia()).hasSize(60);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, "x".repeat(61), BigDecimal.TEN, AHORA, 9L,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, null, BigDecimal.TEN, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.venta(3L, 7L, 20L, 30L, "  ", BigDecimal.TEN, AHORA, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un cobro: tipo COBRO, debe = 0, haber = monto, con recibo y referencia, sin comprobante ni pago ni exceso")
    void cobro() {
        MovimientoCuentaCliente m = MovimientoCuentaCliente.cobro(3L, 7L, 55L, "Recibo N.º 12", new BigDecimal("700.25"),
                AHORA, 9L);

        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getClienteId()).isEqualTo(7L);
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoCuenta.COBRO);
        assertThat(m.getDebe()).isEqualByComparingTo("0");
        assertThat(m.getHaber()).isEqualByComparingTo("700.25");
        assertThat(m.getReciboId()).isEqualTo(55L);
        assertThat(m.getReferencia()).isEqualTo("Recibo N.º 12");
        assertThat(m.getComprobanteId()).isNull();
        assertThat(m.getPagoId()).isNull();
        assertThat(m.isExcedioLimite()).isFalse();
        assertThat(m.getAutorizadoPor()).isNull();
        assertThat(m.getFecha()).isEqualTo(AHORA);
        assertThat(m.getUsuarioId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("el cobro rechaza monto <= 0, recibo nulo y referencia vacía o de más de 60")
    void cobroInvalido() {
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, 55L, "Recibo N.º 1", BigDecimal.ZERO, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, 55L, "Recibo N.º 1", new BigDecimal("-5"), AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, 55L, "Recibo N.º 1", null, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, null, "Recibo N.º 1", BigDecimal.TEN, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, 55L, "", BigDecimal.TEN, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoCuentaCliente.cobro(3L, 7L, 55L, "x".repeat(61), BigDecimal.TEN, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el tipo ANULACION existe (previsto para 3b) y todos los nombres caben en VARCHAR(10)")
    void tipoAnulacion() {
        assertThat(TipoMovimientoCuenta.valueOf("ANULACION")).isEqualTo(TipoMovimientoCuenta.ANULACION);
        assertThat(TipoMovimientoCuenta.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("VENTA", "COBRO", "AJUSTE", "NC", "ANULACION");
        assertThat(Arrays.stream(TipoMovimientoCuenta.values()).map(Enum::name)).allMatch(n -> n.length() <= 10);
    }

    @Test
    @DisplayName("no hay fábrica de ANULACION: 3a no la escribe (solo venta y cobro)")
    void sinFabricaDeAnulacion() {
        assertThat(Arrays.stream(MovimientoCuentaCliente.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)).containsExactlyInAnyOrder("venta", "cobro");
    }

    @Test
    @DisplayName("el libro es append-only: la entidad no tiene setters y todas sus columnas son updatable=false")
    void inmutable() {
        Method[] metodos = MovimientoCuentaCliente.class.getDeclaredMethods();
        assertThat(metodos).isNotEmpty();
        assertThat(Arrays.stream(metodos).filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName))
                .noneMatch(nombre -> nombre.startsWith("set"));

        long columnas = Arrays.stream(MovimientoCuentaCliente.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class)).count();
        assertThat(columnas).isGreaterThanOrEqualTo(10);
        for (Field campo : MovimientoCuentaCliente.class.getDeclaredFields()) {
            Column columna = campo.getAnnotation(Column.class);
            if (columna != null) {
                assertThat(columna.updatable()).as(campo.getName()).isFalse();
            }
        }
    }
}
