package com.ferreteria.clientes.model;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GDS-20, G59: un ajuste de cuenta corriente (Recargo o Descuento) es un registro inmutable con número propio. Dominio puro. */
class AjusteCuentaTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 23, 9, 15);
    private static final String CLAVE = "5d1f0c1e-7a3b-4f9e-8a55-0d2c3b4a5e6f";

    private static AjusteCuenta ajuste(TipoAjuste tipo, BigDecimal importe, String motivo) {
        return AjusteCuenta.nuevo(3L, 7L, 12L, tipo, importe, motivo, CLAVE, AHORA, 9L);
    }

    @Test
    @DisplayName("nuevo(...) guarda ferretería, cliente, número, tipo, importe, motivo recortado, clave, fecha y usuario")
    void nuevoGuardaTodo() {
        AjusteCuenta a = ajuste(TipoAjuste.RECARGO, new BigDecimal("1500.50"), "  Intereses de mora  ");

        assertThat(a.getFerreteriaId()).isEqualTo(3L);
        assertThat(a.getClienteId()).isEqualTo(7L);
        assertThat(a.getNumero()).isEqualTo(12L);
        assertThat(a.getTipo()).isEqualTo(TipoAjuste.RECARGO);
        assertThat(a.getImporte()).isEqualByComparingTo("1500.50");
        assertThat(a.getMotivo()).isEqualTo("Intereses de mora");
        assertThat(a.getClave()).isEqualTo(CLAVE);
        assertThat(a.getFecha()).isEqualTo(AHORA);
        assertThat(a.getUsuarioId()).isEqualTo(9L);
        assertThat(a.getId()).isNull();
    }

    @Test
    @DisplayName("la referencia es «Recargo N.º n» o «Descuento N.º n» según el tipo")
    void referencia() {
        assertThat(ajuste(TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses").referencia()).isEqualTo("Recargo N.º 12");
        assertThat(ajuste(TipoAjuste.DESCUENTO, BigDecimal.TEN, "Bonificación").referencia()).isEqualTo("Descuento N.º 12");
    }

    @Test
    @DisplayName("el importe es mayor que cero, de hasta 2 decimales y cabe en DECIMAL(15,2)")
    void importeValido() {
        assertThat(ajuste(TipoAjuste.RECARGO, new BigDecimal("0.01"), "Intereses").getImporte()).isEqualByComparingTo("0.01");
        assertThat(ajuste(TipoAjuste.RECARGO, new BigDecimal("10.100"), "Intereses").getImporte()).isEqualByComparingTo("10.10");
        assertThat(ajuste(TipoAjuste.RECARGO, new BigDecimal("9999999999999.99"), "Intereses").getImporte())
                .isEqualByComparingTo("9999999999999.99");
        for (BigDecimal malo : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("10.001"),
                new BigDecimal("10000000000000.00")}) {
            assertThatThrownBy(() -> ajuste(TipoAjuste.RECARGO, malo, "Intereses")).as(String.valueOf(malo))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("el motivo, ya recortado, tiene de 5 a 200 caracteres")
    void motivoValido() {
        assertThat(ajuste(TipoAjuste.DESCUENTO, BigDecimal.TEN, "12345").getMotivo()).isEqualTo("12345");
        assertThat(ajuste(TipoAjuste.DESCUENTO, BigDecimal.TEN, "x".repeat(200)).getMotivo()).hasSize(200);
        for (String malo : new String[] {null, "", "    ", "abcd", "  abcd  ", "x".repeat(201)}) {
            assertThatThrownBy(() -> ajuste(TipoAjuste.DESCUENTO, BigDecimal.TEN, malo)).as("[" + malo + "]")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("ferretería, cliente, número positivo, tipo, clave de hasta 36, fecha y usuario son obligatorios")
    void obligatorios() {
        assertThatThrownBy(() -> AjusteCuenta.nuevo(null, 7L, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", CLAVE, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, null, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", CLAVE, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 0L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", CLAVE, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 12L, null, BigDecimal.TEN, "Intereses", CLAVE, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", null, AHORA, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", "x".repeat(37),
                AHORA, 9L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", CLAVE, null, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AjusteCuenta.nuevo(3L, 7L, 12L, TipoAjuste.RECARGO, BigDecimal.TEN, "Intereses", CLAVE, AHORA, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el tipo cabe en VARCHAR(10) y son solo RECARGO y DESCUENTO")
    void tipos() {
        assertThat(Arrays.stream(TipoAjuste.values()).map(Enum::name)).containsExactlyInAnyOrder("RECARGO", "DESCUENTO")
                .allMatch(n -> n.length() <= 10);
    }

    @Test
    @DisplayName("es inmutable: mapeado a ajuste_cuenta, sin setters y con todas sus columnas updatable=false")
    void inmutable() {
        assertThat(AjusteCuenta.class.getAnnotation(Table.class).name()).isEqualTo("ajuste_cuenta");
        assertThat(Arrays.stream(AjusteCuenta.class.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)).contains("nuevo", "getImporte").noneMatch(nombre -> nombre.startsWith("set"));

        long columnas = Arrays.stream(AjusteCuenta.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
                .count();
        assertThat(columnas).isEqualTo(9);
        for (Field campo : AjusteCuenta.class.getDeclaredFields()) {
            Column columna = campo.getAnnotation(Column.class);
            if (columna != null) {
                assertThat(columna.updatable()).as(campo.getName()).isFalse();
            }
        }
    }
}
