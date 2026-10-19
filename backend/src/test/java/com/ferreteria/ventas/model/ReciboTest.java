package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-036, CC-041: el recibo es inmutable y solo se emite en efectivo o transferencia. Dominio puro. */
class ReciboTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 10, 30);
    private static final String CLAVE = "3f2b8c1e-6d1a-4f5e-9a47-0c1b2d3e4f5a";

    private static Recibo emitir(MedioPago medio, String total) {
        return Recibo.emitir(3L, 12L, CLAVE, 7L, "Constructora Sur", 40L, 9L, AHORA, medio, new BigDecimal(total));
    }

    @Test
    @DisplayName("emitir copia todos los datos: ferretería, número, clave, cliente y su nombre, caja, usuario, fecha, medio y total")
    void emitirCopiaLosDatos() {
        Recibo r = emitir(MedioPago.EFECTIVO, "1500.50");

        assertThat(r.getFerreteriaId()).isEqualTo(3L);
        assertThat(r.getNumero()).isEqualTo(12L);
        assertThat(r.getClaveIdempotencia()).isEqualTo(CLAVE);
        assertThat(r.getClienteId()).isEqualTo(7L);
        assertThat(r.getClienteNombre()).isEqualTo("Constructora Sur");
        assertThat(r.getCajaId()).isEqualTo(40L);
        assertThat(r.getUsuarioId()).isEqualTo(9L);
        assertThat(r.getFecha()).isEqualTo(AHORA);
        assertThat(r.getMedio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(r.getTotal()).isEqualByComparingTo("1500.50");
        assertThat(r.getId()).isNull();
    }

    @Test
    @DisplayName("la transferencia también entra, y un UUID real entra como clave")
    void transferenciaYUuid() {
        Recibo r = Recibo.emitir(3L, 1L, UUID.randomUUID().toString(), 7L, "Ana", 40L, 9L, AHORA, MedioPago.TRANSFERENCIA,
                new BigDecimal("0.01"));

        assertThat(r.getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(r.getTotal()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("solo EFECTIVO y TRANSFERENCIA: cualquier otro medio, CUENTA_CORRIENTE incluido, se rechaza")
    void medioInvalido() {
        for (MedioPago medio : MedioPago.values()) {
            if (medio == MedioPago.EFECTIVO || medio == MedioPago.TRANSFERENCIA) {
                continue;
            }
            assertThatThrownBy(() -> emitir(medio, "100.00")).as(medio.name()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MedioPago.valueOf("CUENTA_CORRIENTE")).isNotNull();
        assertThatThrownBy(() -> emitir(MedioPago.CUENTA_CORRIENTE, "100.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emitir(null, "100.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el total tiene que ser mayor que cero y de a lo sumo 2 decimales")
    void totalInvalido() {
        assertThatThrownBy(() -> emitir(MedioPago.EFECTIVO, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emitir(MedioPago.EFECTIVO, "-5.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emitir(MedioPago.EFECTIVO, "10.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 12L, CLAVE, 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(emitir(MedioPago.EFECTIVO, "10.100").getTotal()).isEqualByComparingTo("10.10");
    }

    @Test
    @DisplayName("la clave no puede estar vacía ni pasar de 36 caracteres")
    void claveInvalida() {
        assertThatThrownBy(() -> Recibo.emitir(3L, 12L, null, 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 12L, " ", 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 12L, "x".repeat(37), 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO,
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Recibo.emitir(3L, 12L, "x".repeat(36), 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN)
                .getClaveIdempotencia()).hasSize(36);
    }

    @Test
    @DisplayName("el número es positivo")
    void numeroInvalido() {
        assertThatThrownBy(() -> Recibo.emitir(3L, 0L, CLAVE, 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, -1L, CLAVE, 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el nombre del cliente se copia y no puede estar vacío ni pasar de 120 caracteres")
    void nombreDelCliente() {
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, null, 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, "  ", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, "n".repeat(121), 40L, 9L, AHORA, MedioPago.EFECTIVO,
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Recibo.emitir(3L, 1L, CLAVE, 7L, "n".repeat(120), 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN)
                .getClienteNombre()).hasSize(120);
    }

    @Test
    @DisplayName("ids y fecha obligatorios")
    void obligatorios() {
        assertThatThrownBy(() -> Recibo.emitir(null, 1L, CLAVE, 7L, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, null, "Ana", 40L, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, "Ana", null, 9L, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, "Ana", 40L, null, AHORA, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recibo.emitir(3L, 1L, CLAVE, 7L, "Ana", 40L, 9L, null, MedioPago.EFECTIVO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el recibo es inmutable: sin setters y con todas sus columnas updatable=false, mapeado a la tabla recibo")
    void inmutable() {
        assertThat(Recibo.class.getAnnotation(Table.class).name()).isEqualTo("recibo");
        Method[] metodos = Recibo.class.getDeclaredMethods();
        assertThat(Arrays.stream(metodos).filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName))
                .contains("emitir", "getNumero").noneMatch(nombre -> nombre.startsWith("set"));

        long columnas = Arrays.stream(Recibo.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
                .count();
        assertThat(columnas).isEqualTo(10);
        for (Field campo : Recibo.class.getDeclaredFields()) {
            Column columna = campo.getAnnotation(Column.class);
            if (columna != null) {
                assertThat(columna.updatable()).as(campo.getName()).isFalse();
            }
        }
    }
}
