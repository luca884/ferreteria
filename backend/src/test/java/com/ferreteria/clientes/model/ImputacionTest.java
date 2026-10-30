package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-026, CC-041: una imputación es un renglón inmutable «este recibo canceló tanto de este comprobante». Dominio puro. */
class ImputacionTest {

    @Test
    @DisplayName("de(...) guarda ferretería, cliente, recibo, comprobante e importe")
    void deGuardaTodo() {
        Imputacion i = Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("1000.50"));

        assertThat(i.getFerreteriaId()).isEqualTo(3L);
        assertThat(i.getClienteId()).isEqualTo(7L);
        assertThat(i.getReciboId()).isEqualTo(55L);
        assertThat(i.getComprobanteId()).isEqualTo(20L);
        assertThat(i.getImporte()).isEqualByComparingTo("1000.50");
        assertThat(i.getId()).isNull();
    }

    @Test
    @DisplayName("el importe es mayor que cero y de a lo sumo 2 decimales (10 y 10.00 valen lo mismo; 10.001 no)")
    void importeValido() {
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("10")).getImporte()).isEqualByComparingTo("10");
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("10.00")).getImporte()).isEqualByComparingTo("10");
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("10.100")).getImporte()).isEqualByComparingTo("10.10");
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("0.01")).getImporte()).isEqualByComparingTo("0.01");
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, 55L, 20L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, 55L, 20L, new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, 55L, 20L, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ferretería, cliente, recibo y comprobante son obligatorios")
    void idsObligatorios() {
        assertThatThrownBy(() -> Imputacion.de(null, 7L, 55L, 20L, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, null, 55L, 20L, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, null, 20L, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, 55L, null, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GDS-20: el origen es un recibo o un Descuento y el destino un comprobante o un Recargo; las cuatro combinaciones se guardan")
    void origenYDestino() {
        Imputacion reciboAComprobante = Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN);
        Imputacion reciboARecargo = Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), Imputacion.Destino.ajuste(81L),
                BigDecimal.TEN);
        Imputacion descuentoAComprobante = Imputacion.de(3L, 7L, Imputacion.Origen.ajuste(82L),
                Imputacion.Destino.comprobante(20L), BigDecimal.TEN);
        Imputacion descuentoARecargo = Imputacion.de(3L, 7L, Imputacion.Origen.ajuste(82L), Imputacion.Destino.ajuste(81L),
                BigDecimal.TEN);

        assertThat(reciboAComprobante.getReciboId()).isEqualTo(55L);
        assertThat(reciboAComprobante.getComprobanteId()).isEqualTo(20L);
        assertThat(reciboAComprobante.getAjusteOrigenId()).isNull();
        assertThat(reciboAComprobante.getAjusteDestinoId()).isNull();
        assertThat(reciboARecargo.getReciboId()).isEqualTo(55L);
        assertThat(reciboARecargo.getComprobanteId()).isNull();
        assertThat(reciboARecargo.getAjusteDestinoId()).isEqualTo(81L);
        assertThat(descuentoAComprobante.getReciboId()).isNull();
        assertThat(descuentoAComprobante.getAjusteOrigenId()).isEqualTo(82L);
        assertThat(descuentoAComprobante.getComprobanteId()).isEqualTo(20L);
        assertThat(descuentoARecargo.getAjusteOrigenId()).isEqualTo(82L);
        assertThat(descuentoARecargo.getAjusteDestinoId()).isEqualTo(81L);
        assertThat(descuentoARecargo.isVigente()).isTrue();
    }

    @Test
    @DisplayName("GDS-20: la imputación pedida no es automática; la que crea sola la deuda nueva al consumir crédito sí")
    void automatica() {
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, BigDecimal.TEN).isAutomatica()).isFalse();
        assertThat(Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), Imputacion.Destino.comprobante(20L), BigDecimal.TEN)
                .isAutomatica()).isFalse();
        Imputacion consumida = Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN, true);
        assertThat(consumida.isAutomatica()).isTrue();
        assertThat(consumida.isVigente()).isTrue();
        assertThat(consumida.getReciboId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("CU-08 (V39): la nota de crédito es un origen más: guarda nc_origen_id, sin recibo ni Descuento, vigente")
    void origenNotaDeCredito() {
        Imputacion imputacion = Imputacion.de(3L, 7L, Imputacion.Origen.notaDeCredito(90L), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN);

        assertThat(imputacion.getNcOrigenId()).isEqualTo(90L);
        assertThat(imputacion.getReciboId()).isNull();
        assertThat(imputacion.getAjusteOrigenId()).isNull();
        assertThat(imputacion.getComprobanteId()).isEqualTo(20L);
        assertThat(imputacion.isAutomatica()).isFalse();
        assertThat(imputacion.isVigente()).isTrue();
    }

    @Test
    @DisplayName("GDS-20: exactamente un origen y exactamente un destino (los dos CHECK de la base, antes en el dominio)")
    void unSoloOrigenYUnSoloDestino() {
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, new Imputacion.Origen(55L, 82L, null), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, new Imputacion.Origen(55L, null, 90L), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, new Imputacion.Origen(null, null, null), Imputacion.Destino.comprobante(20L),
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), new Imputacion.Destino(20L, 81L),
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), new Imputacion.Destino(null, null),
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, null, Imputacion.Destino.comprobante(20L), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imputacion.de(3L, 7L, Imputacion.Origen.recibo(55L), null, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una imputación nueva nace vigente (CC-063)")
    void nacesVigente() {
        assertThat(Imputacion.de(3L, 7L, 55L, 20L, BigDecimal.TEN).isVigente()).isTrue();
        assertThat(Imputacion.de(4L, 8L, 56L, 21L, new BigDecimal("0.01")).isVigente()).isTrue();
    }

    @Test
    @DisplayName("es append-only: mapeada a imputacion, sin setters y con todas sus columnas updatable=false (la vigencia solo cambia por la query de la anulación)")
    void inmutable() {
        assertThat(Imputacion.class.getAnnotation(Table.class).name()).isEqualTo("imputacion");
        Method[] metodos = Imputacion.class.getDeclaredMethods();
        assertThat(Arrays.stream(metodos).filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName))
                .contains("de", "getImporte", "isVigente").noneMatch(nombre -> nombre.startsWith("set"));

        long columnas = Arrays.stream(Imputacion.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
                .count();
        // 6 de V21 + ajuste_origen_id, ajuste_destino_id y automatica de V28 (GDS-20) + nc_origen_id de V39 (CU-08)
        assertThat(columnas).isEqualTo(10);
        for (Field campo : Imputacion.class.getDeclaredFields()) {
            Column columna = campo.getAnnotation(Column.class);
            if (columna != null) {
                assertThat(columna.updatable()).as(campo.getName()).isFalse();
            }
        }
        Column vigente = Arrays.stream(Imputacion.class.getDeclaredFields()).filter(f -> f.getName().equals("vigente"))
                .findFirst().orElseThrow().getAnnotation(Column.class);
        assertThat(vigente.nullable()).isFalse();
    }
}
