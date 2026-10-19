package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CC-036: el numerador de recibos es una fila por ferretería que solo se mueve con el UPSERT nativo del repositorio, nunca
 * desde la entidad; igual que {@link NumeradorComprobante}. Dominio puro (estructura de la entidad).
 */
class NumeradorReciboTest {

    @Test
    @DisplayName("es una entidad de la tabla numerador_recibo, con id, ferreteria_id y ultimo_numero")
    void mapeo() {
        assertThat(NumeradorRecibo.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(NumeradorRecibo.class.getAnnotation(Table.class).name()).isEqualTo("numerador_recibo");
        assertThat(Arrays.stream(NumeradorRecibo.class.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)).containsExactlyInAnyOrder("getId", "getFerreteriaId", "getUltimoNumero");
    }

    @Test
    @DisplayName("no se escribe desde la entidad: sin setters y columnas updatable=false")
    void soloLecturaDesdeLaEntidad() {
        assertThat(Arrays.stream(NumeradorRecibo.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(n -> n.startsWith("set"));
        long columnas = Arrays.stream(NumeradorRecibo.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class)).peek(f -> assertThat(f.getAnnotation(Column.class).updatable())
                        .as(f.getName()).isFalse())
                .count();
        assertThat(columnas).isEqualTo(2);
    }
}
