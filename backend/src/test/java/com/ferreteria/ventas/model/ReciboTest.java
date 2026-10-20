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
    @DisplayName("emitir deja el recibo EMITIDO y sin auditoría de anulación (CC-069)")
    void emitirDejaElReciboEmitido() {
        Recibo r = emitir(MedioPago.EFECTIVO, "100.00");

        assertThat(r.getEstado()).isEqualTo(EstadoRecibo.EMITIDO);
        assertThat(r.isAnulado()).isFalse();
        assertThat(r.getFechaAnulacion()).isNull();
        assertThat(r.getUsuarioAnulacionId()).isNull();
        assertThat(r.getCajaAnulacionId()).isNull();
        assertThat(r.getMotivoAnulacion()).isNull();
    }

    @Test
    @DisplayName("anular pasa a ANULADO y setea fecha, usuario, caja y motivo (recortado); el resto del recibo no cambia (CC-062, CC-067)")
    void anularSeteaLaAuditoria() {
        Recibo r = emitir(MedioPago.EFECTIVO, "1500.50");
        LocalDateTime cuando = AHORA.plusDays(2);

        r.anular(21L, cuando, 55L, "  Cobro mal cargado  ");

        assertThat(r.getEstado()).isEqualTo(EstadoRecibo.ANULADO);
        assertThat(r.isAnulado()).isTrue();
        assertThat(r.getFechaAnulacion()).isEqualTo(cuando);
        assertThat(r.getUsuarioAnulacionId()).isEqualTo(21L);
        assertThat(r.getCajaAnulacionId()).isEqualTo(55L);
        assertThat(r.getMotivoAnulacion()).isEqualTo("Cobro mal cargado");
        // lo que describe cómo se emitió no se toca (la caja original, el usuario que cobró y el total siguen siendo los de la emisión)
        assertThat(r.getCajaId()).isEqualTo(40L);
        assertThat(r.getUsuarioId()).isEqualTo(9L);
        assertThat(r.getFecha()).isEqualTo(AHORA);
        assertThat(r.getTotal()).isEqualByComparingTo("1500.50");
    }

    @Test
    @DisplayName("anular con otros valores: la auditoría copia lo que se le da (no hay valores fijos)")
    void anularTriangula() {
        Recibo r = emitir(MedioPago.TRANSFERENCIA, "10.00");

        r.anular(3L, AHORA.plusHours(1), 77L, "12345");

        assertThat(r.getUsuarioAnulacionId()).isEqualTo(3L);
        assertThat(r.getCajaAnulacionId()).isEqualTo(77L);
        assertThat(r.getFechaAnulacion()).isEqualTo(AHORA.plusHours(1));
        assertThat(r.getMotivoAnulacion()).isEqualTo("12345");
    }

    @Test
    @DisplayName("anular dos veces es IllegalStateException y la primera auditoría queda intacta (CC-060)")
    void dobleAnulacion() {
        Recibo r = emitir(MedioPago.EFECTIVO, "100.00");
        r.anular(21L, AHORA.plusDays(1), 55L, "Cobro mal cargado");

        assertThatThrownBy(() -> r.anular(22L, AHORA.plusDays(2), 56L, "Otro motivo distinto"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(r.getUsuarioAnulacionId()).isEqualTo(21L);
        assertThat(r.getCajaAnulacionId()).isEqualTo(55L);
        assertThat(r.getFechaAnulacion()).isEqualTo(AHORA.plusDays(1));
        assertThat(r.getMotivoAnulacion()).isEqualTo("Cobro mal cargado");
    }

    @Test
    @DisplayName("anular con usuario, fecha, caja o motivo nulos es IllegalArgumentException y el recibo sigue EMITIDO")
    void anularConNulos() {
        Recibo r = emitir(MedioPago.EFECTIVO, "100.00");

        assertThatThrownBy(() -> r.anular(null, AHORA, 55L, "Cobro mal cargado")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.anular(21L, null, 55L, "Cobro mal cargado")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.anular(21L, AHORA, null, "Cobro mal cargado")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.anular(21L, AHORA, 55L, null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(r.getEstado()).isEqualTo(EstadoRecibo.EMITIDO);
        assertThat(r.getMotivoAnulacion()).isNull();
    }

    @Test
    @DisplayName("el motivo: vacío, en blanco, de menos de 5 caracteres tras recortar o de más de 200 se rechaza; 5 y 200 entran (alineado con ck_recibo_anulacion)")
    void motivoDeLaAnulacion() {
        for (String invalido : new String[] {"", "    ", "abcd", "  abcd  ", "x".repeat(201)}) {
            Recibo r = emitir(MedioPago.EFECTIVO, "100.00");
            assertThatThrownBy(() -> r.anular(21L, AHORA, 55L, invalido)).as("[" + invalido + "]")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(r.isAnulado()).as("[" + invalido + "] no anula").isFalse();
        }
        Recibo cinco = emitir(MedioPago.EFECTIVO, "100.00");
        cinco.anular(21L, AHORA, 55L, "12345");
        Recibo doscientos = emitir(MedioPago.EFECTIVO, "100.00");
        doscientos.anular(21L, AHORA, 55L, "x".repeat(200));

        assertThat(cinco.getMotivoAnulacion()).isEqualTo("12345");
        assertThat(doscientos.getMotivoAnulacion()).hasSize(200);
    }

    @Test
    @DisplayName("el recibo sigue sin setters: anular es el único mutador, mapeado a la tabla recibo")
    void inmutable() {
        assertThat(Recibo.class.getAnnotation(Table.class).name()).isEqualTo("recibo");
        Method[] metodos = Recibo.class.getDeclaredMethods();
        assertThat(Arrays.stream(metodos).filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName))
                .contains("emitir", "anular", "getNumero").noneMatch(nombre -> nombre.startsWith("set"));
        assertThat(Arrays.stream(metodos).filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName).filter(nombre -> !nombre.startsWith("get") && !nombre.startsWith("is")))
                .containsExactly("anular");
    }

    @Test
    @DisplayName("las 10 columnas de 3a siguen updatable=false y las 5 de la anulación (estado y auditoría) son las únicas updatable")
    void columnasActualizables() {
        java.util.Set<String> deLaAnulacion = java.util.Set.of("estado", "fechaAnulacion", "usuarioAnulacionId",
                "cajaAnulacionId", "motivoAnulacion");
        long columnas = Arrays.stream(Recibo.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
                .count();

        assertThat(columnas).isEqualTo(15);
        for (Field campo : Recibo.class.getDeclaredFields()) {
            Column columna = campo.getAnnotation(Column.class);
            if (columna == null) {
                continue;
            }
            assertThat(columna.updatable()).as(campo.getName()).isEqualTo(deLaAnulacion.contains(campo.getName()));
        }
        assertThat(Arrays.stream(Recibo.class.getDeclaredFields()).map(Field::getName)).containsAll(deLaAnulacion);
    }
}
