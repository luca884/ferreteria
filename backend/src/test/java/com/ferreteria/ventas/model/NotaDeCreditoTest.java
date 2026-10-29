package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * La nota de credito y la anulacion interna como comprobante (CU-08, cu-08-nota-de-credito C3): una NOTA_CREDITO asociada a
 * la venta, con quien y cuando, sin caja, con los totales y los renglones (con su costo) copiados en positivo.
 */
class NotaDeCreditoTest {

    private static final LocalDateTime VENDIDA = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final LocalDateTime ANULADA = LocalDateTime.of(2026, 10, 29, 18, 30);

    private static Comprobante venta() {
        RenglonVenta renglon = RenglonVenta.de(new BigDecimal("100.00"), new BigDecimal("2"),
                new BigDecimal("21.00"), new BigDecimal("10.00"), new BigDecimal("5.00"));
        TotalesVenta totales = TotalesVenta.de(List.of(renglon));
        Comprobante venta = Comprobante.venta(7L, 41, "clave-venta", 3L, totales, 11L, new BigDecimal("5.00"), 21L, 31L,
                true, 51L, "Juan Perez", VENDIDA);
        ReflectionTestUtils.setField(venta, "id", 400L);
        return venta;
    }

    @Test
    @DisplayName("la NC copia de la venta los totales, el descuento, el recargo y el cliente, en positivo; sin caja, sin "
            + "presupuesto ni remito, y con su numero, su clave, su usuario y su fecha")
    void laNotaCopiaLaVenta() {
        Comprobante venta = venta();

        Comprobante nota = Comprobante.notaDeCredito(venta, 42, "clave-nota", 9L, ANULADA, null);

        assertThat(nota.getTipo()).isEqualTo(TipoComprobante.NOTA_CREDITO);
        assertThat(nota.getComprobanteAsociadoId()).isEqualTo(400L);
        assertThat(nota.getMotivoAnulacion()).isNull();
        assertThat(nota.esAnulacionInterna()).isFalse();
        assertThat(nota.getEstado()).isEqualTo(EstadoComprobante.SIN_FACTURAR);
        assertThat(nota.getFerreteriaId()).isEqualTo(7L);
        assertThat(nota.getNumero()).isEqualTo(42L);
        assertThat(nota.getClaveIdempotencia()).isEqualTo("clave-nota");
        assertThat(nota.getUsuarioId()).isEqualTo(9L);
        assertThat(nota.getFecha()).isEqualTo(ANULADA);
        assertThat(nota.getCajaId()).isNull();
        assertThat(nota.getPresupuestoId()).isNull();
        assertThat(nota.getRemitoId()).isNull();
        assertThat(nota.getClienteId()).isEqualTo(51L);
        assertThat(nota.getClienteNombre()).isEqualTo("Juan Perez");
        assertThat(nota.getNeto()).isEqualByComparingTo(venta.getNeto()).isPositive();
        assertThat(nota.getIva()).isEqualByComparingTo(venta.getIva());
        assertThat(nota.getTotal()).isEqualByComparingTo(venta.getTotal()).isPositive();
        assertThat(nota.getSubtotal()).isEqualByComparingTo(venta.getSubtotal());
        assertThat(nota.getDescuentoPorcentaje()).isEqualByComparingTo("5.00");
        assertThat(nota.getRecargo()).isEqualByComparingTo(venta.getRecargo());
    }

    @Test
    @DisplayName("la anulacion interna lleva el motivo sin espacios de mas y no se factura nunca")
    void laAnulacionInternaLlevaElMotivo() {
        Comprobante anulacion = Comprobante.notaDeCredito(venta(), 42, "clave-nota", 9L, ANULADA, "  Se cobro dos veces ");

        assertThat(anulacion.getMotivoAnulacion()).isEqualTo("Se cobro dos veces");
        assertThat(anulacion.esAnulacionInterna()).isTrue();
        assertThat(anulacion.getEstado()).isEqualTo(EstadoComprobante.NO_FACTURABLE);
    }

    @Test
    @DisplayName("solo anula una venta ya guardada, y un motivo en blanco no es una anulacion interna")
    void soloAnulaUnaVentaGuardada() {
        Comprobante nota = Comprobante.notaDeCredito(venta(), 42, "clave-nota", 9L, ANULADA, null);
        ReflectionTestUtils.setField(nota, "id", 401L);
        Comprobante sinGuardar = Comprobante.venta(7L, 43, "otra", 3L,
                TotalesVenta.de(List.of(RenglonVenta.de(new BigDecimal("10.00"), BigDecimal.ONE,
                        new BigDecimal("21.00")))), 11L, VENDIDA);

        assertThatThrownBy(() -> Comprobante.notaDeCredito(nota, 44, "c", 9L, ANULADA, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Comprobante.notaDeCredito(sinGuardar, 44, "c", 9L, ANULADA, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Comprobante.notaDeCredito(venta(), 44, "c", 9L, ANULADA, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cada renglon de la NC es la copia del de la venta, con su costo, en positivo")
    void elRenglonSeCopiaConSuCosto() {
        RenglonVenta calculado = RenglonVenta.de(new BigDecimal("50.00"), new BigDecimal("3"),
                new BigDecimal("21.00"), new BigDecimal("10.00"), BigDecimal.ZERO);
        ItemComprobante vendido = ItemComprobante.de(7L, 400L, 2, 61L, "Tornillo", "UNIDAD", "IVA_21",
                new BigDecimal("12.3456"), calculado);

        ItemComprobante copia = ItemComprobante.copiaParaLaNota(vendido, 401L);

        assertThat(copia.getComprobanteId()).isEqualTo(401L);
        assertThat(copia.getFerreteriaId()).isEqualTo(7L);
        assertThat(copia).usingRecursiveComparison().ignoringFields("id", "comprobanteId").isEqualTo(vendido);
        assertThat(copia.getCosto()).isPositive().isEqualByComparingTo(vendido.getCosto());
        assertThat(copia.getCantidad()).isEqualByComparingTo("3");
    }
}
