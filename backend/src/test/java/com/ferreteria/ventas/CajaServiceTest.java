package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.ReciboRepository;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.DetalleCaja;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** CU-10: la caja en la que se vende, con el reloj fijo. */
@ExtendWith(MockitoExtension.class)
class CajaServiceTest {

    /** 02:30 UTC del 13/10 = 23:30 del 12/10 en Buenos Aires. */
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-13T02:30:00Z"), HoraArgentina.ZONA);

    @Mock
    CajaRepository cajas;

    @Mock
    MovimientoCajaRepository movimientos;

    @Mock
    PagoRepository pagos;

    @Mock
    ReciboRepository recibos;

    @Mock
    ComprobanteRepository comprobantes;

    CajaService service;

    @BeforeEach
    void preparar() {
        service = new CajaService(cajas, movimientos, pagos, recibos, comprobantes, RELOJ);
    }

    private static Caja abiertaEl(LocalDateTime apertura) {
        Caja caja = Caja.abrir(3L, 9L, BigDecimal.ZERO, null, apertura);
        ReflectionTestUtils.setField(caja, "id", 40L);
        return caja;
    }

    private CobranzaRechazadaException rechazoDeCobro() {
        Throwable error = catchThrowable(service::cajaParaCobrar);
        assertThat(error).isInstanceOf(CobranzaRechazadaException.class);
        return (CobranzaRechazadaException) error;
    }

    private VentaRechazadaException rechazo() {
        Throwable error = catchThrowable(service::cajaParaVender);
        assertThat(error).isInstanceOf(VentaRechazadaException.class);
        return (VentaRechazadaException) error;
    }

    @Test
    @DisplayName("sin caja abierta: CAJA_CERRADA «Abrí la caja para vender.»")
    void sinCaja() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo();

        assertThat(e.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("Abrí la caja para vender.");
    }

    @Test
    @DisplayName("la caja abierta hoy (fecha de Buenos Aires, aunque en UTC ya sea manana) es la de la venta")
    void cajaDeHoy() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 12, 8, 0))));

        assertThat(service.cajaParaVender()).isEqualTo(40L);
    }

    @Test
    @DisplayName("la caja abierta ayer: CAJA_DE_OTRO_DIA con la fecha de esa caja")
    void cajaDeOtroDia() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 11, 8, 0))));

        VentaRechazadaException e = rechazo();

        assertThat(e.motivo()).isEqualTo(Motivo.CAJA_DE_OTRO_DIA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de vender.");
    }

    // ---- CCT3-305 (CC-035, CC-054): la caja en la que se cobra ----

    @Test
    @DisplayName("cobrar sin caja abierta: CAJA_CERRADA «Abrí la caja para cobrar.» (422)")
    void cobrarSinCaja() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.empty());

        CobranzaRechazadaException e = rechazoDeCobro();

        assertThat(e.motivo()).isEqualTo(CobranzaRechazadaException.Motivo.CAJA_CERRADA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("Abrí la caja para cobrar.");
    }

    @Test
    @DisplayName("cobrar con la caja de ayer: CAJA_DE_OTRO_DIA con la fecha dd/MM de esa caja")
    void cobrarConCajaDeOtroDia() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 11, 8, 0))));

        CobranzaRechazadaException e = rechazoDeCobro();

        assertThat(e.motivo()).isEqualTo(CobranzaRechazadaException.Motivo.CAJA_DE_OTRO_DIA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de cobrar.");
    }

    @Test
    @DisplayName("cobrar con la caja abierta hoy (fecha de Buenos Aires) devuelve su id; toma la MISMA caja compartida (FOR SHARE) que vender")
    void cobrarConCajaDeHoy() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 12, 8, 0))));

        assertThat(service.cajaParaCobrar()).isEqualTo(40L);
        assertThat(service.cajaParaVender()).isEqualTo(40L);
        verify(cajas, org.mockito.Mockito.times(2)).abiertaParaVenderScoped();
        verify(cajas, never()).abiertaScoped();
    }

    @Test
    @DisplayName("regresión: vender mantiene sus mensajes («…para vender») aunque cobrar tenga los suyos")
    void venderConservaSusMensajes() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.empty());
        assertThat(rechazo().getMessage()).isEqualTo("Abrí la caja para vender.");

        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 11, 8, 0))));
        assertThat(rechazo().getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de vender.");
    }

    @Test
    @DisplayName("cajaParaCobrar y cajaParaVender exigen transacción (MANDATORY): el lock compartido tiene que vivir hasta el commit del cobro")
    void exigenTransaccion() throws NoSuchMethodException {
        for (String nombre : new String[] {"cajaParaCobrar", "cajaParaVender"}) {
            Method metodo = CajaService.class.getMethod(nombre);
            assertThat(metodo.getAnnotation(Transactional.class)).as(nombre).isNotNull();
            assertThat(metodo.getAnnotation(Transactional.class).propagation()).as(nombre).isEqualTo(Propagation.MANDATORY);
        }
    }

    // ---- CCT3-304 (CC-053, CJ-R-012, CJ-R-013): el resumen de caja suma los recibos ----

    @Test
    @DisplayName("el detalle de una caja abierta pasa las cobranzas por medio al resumen: efectivo suma al esperado, transferencia no")
    void detalleSumaLasCobranzas() {
        TenantContext.setCurrentFerreteriaId(3L);
        try {
            Caja caja = abiertaEl(LocalDateTime.of(2026, 10, 12, 8, 0));
            when(cajas.findScoped(40L)).thenReturn(Optional.of(caja));
            when(pagos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("1000.00")));
            when(recibos.totalesDeCajaScoped(40L)).thenReturn(
                    Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00"), MedioPago.TRANSFERENCIA, new BigDecimal("300.00")));

            DetalleCaja detalle = service.detalle(40L);

            assertThat(detalle.resumen().cobranzasPorMedio().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
            assertThat(detalle.resumen().cobranzasPorMedio().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
            assertThat(detalle.resumen().efectivoEsperado()).isEqualByComparingTo("1700"); // 0 + 1000 + 700
            assertThat(detalle.resumen().totalVentas()).isEqualByComparingTo("1000");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("el cierre toma la caja con lock exclusivo ANTES de leer los recibos y guarda un esperado que incluye la cobranza en efectivo")
    void cierreSumaLasCobranzasDespuesDelLock() {
        Caja caja = Caja.abrir(3L, 9L, new BigDecimal("5000.00"), null, LocalDateTime.of(2026, 10, 12, 8, 0));
        ReflectionTestUtils.setField(caja, "id", 40L);
        when(cajas.paraCerrarScoped(40L)).thenReturn(Optional.of(caja));
        when(pagos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("1000.00")));
        when(recibos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00")));

        DetalleCaja detalle = service.cerrar(40L, new BigDecimal("6700.00"), 9L);

        InOrder orden = inOrder(cajas, recibos);
        orden.verify(cajas).paraCerrarScoped(40L);
        orden.verify(recibos).totalesDeCajaScoped(40L);
        assertThat(caja.getEfectivoEsperado()).isEqualByComparingTo("6700");
        assertThat(caja.getDiferencia()).isEqualByComparingTo("0");
        assertThat(detalle.resumen().cobranzasEnEfectivo()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("sin recibos el esperado es el de siempre (cajas viejas: cobranzas en 0)")
    void sinRecibosNoCambiaNada() {
        Caja caja = Caja.abrir(3L, 9L, new BigDecimal("5000.00"), null, LocalDateTime.of(2026, 10, 12, 8, 0));
        ReflectionTestUtils.setField(caja, "id", 40L);
        when(cajas.paraCerrarScoped(40L)).thenReturn(Optional.of(caja));
        when(pagos.totalesDeCajaScoped(anyLong())).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("1000.00")));

        DetalleCaja detalle = service.cerrar(40L, new BigDecimal("5950.00"), 9L);

        assertThat(caja.getEfectivoEsperado()).isEqualByComparingTo("6000");
        assertThat(caja.getDiferencia()).isEqualByComparingTo("-50");
        assertThat(detalle.resumen().totalCobranzas()).isEqualByComparingTo("0");
    }

    // ---- CCT3B1-303 (CC-081, CC-082, CJ-R-012): el resumen suma las anulaciones por la caja de anulación ----

    @Test
    @DisplayName("el detalle pasa las anulaciones por medio al resumen: efectivo resta del esperado, la cobranza sigue bruta")
    void detalleRestaLasAnulaciones() {
        TenantContext.setCurrentFerreteriaId(3L);
        try {
            Caja caja = abiertaEl(LocalDateTime.of(2026, 10, 12, 8, 0));
            when(cajas.findScoped(40L)).thenReturn(Optional.of(caja));
            when(pagos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("1000.00")));
            when(recibos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00")));
            when(recibos.totalesAnuladosDeCajaScoped(40L)).thenReturn(
                    Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00"), MedioPago.TRANSFERENCIA, new BigDecimal("300.00")));

            DetalleCaja detalle = service.detalle(40L);

            assertThat(detalle.resumen().anulacionesPorMedio().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
            assertThat(detalle.resumen().anulacionesPorMedio().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
            assertThat(detalle.resumen().cobranzasEnEfectivo()).isEqualByComparingTo("700"); // brutas
            assertThat(detalle.resumen().efectivoEsperado()).isEqualByComparingTo("1000"); // 0 + 1000 + 700 - 700
            assertThat(detalle.resumen().totalAnulaciones()).isEqualByComparingTo("1000");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("el cierre lee las anulaciones DESPUÉS del lock exclusivo de la caja y las guarda en el esperado (caja vieja: 0)")
    void cierreRestaLasAnulacionesDespuesDelLock() {
        Caja caja = Caja.abrir(3L, 9L, new BigDecimal("5000.00"), null, LocalDateTime.of(2026, 10, 12, 8, 0));
        ReflectionTestUtils.setField(caja, "id", 40L);
        when(cajas.paraCerrarScoped(40L)).thenReturn(Optional.of(caja));
        when(pagos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("1000.00")));
        when(recibos.totalesDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00")));
        when(recibos.totalesAnuladosDeCajaScoped(40L)).thenReturn(Map.of(MedioPago.EFECTIVO, new BigDecimal("700.00")));

        DetalleCaja detalle = service.cerrar(40L, new BigDecimal("6000.00"), 9L);

        InOrder orden = inOrder(cajas, recibos);
        orden.verify(cajas).paraCerrarScoped(40L);
        orden.verify(recibos).totalesAnuladosDeCajaScoped(40L);
        assertThat(caja.getEfectivoEsperado()).isEqualByComparingTo("6000"); // 5000 + 1000 + 700 - 700
        assertThat(caja.getDiferencia()).isEqualByComparingTo("0");
        assertThat(detalle.resumen().totalAnulaciones()).isEqualByComparingTo("700");
    }

    // ---- CCT3B1-304 (CC-058, CC-085): la caja en la que se anula ----

    private CobranzaRechazadaException rechazoDeAnulacion() {
        Throwable error = catchThrowable(service::cajaParaAnular);
        assertThat(error).isInstanceOf(CobranzaRechazadaException.class);
        return (CobranzaRechazadaException) error;
    }

    @Test
    @DisplayName("anular sin caja abierta: CAJA_CERRADA «Abrí la caja para anular.» (422)")
    void anularSinCaja() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.empty());

        CobranzaRechazadaException e = rechazoDeAnulacion();

        assertThat(e.motivo()).isEqualTo(CobranzaRechazadaException.Motivo.CAJA_CERRADA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("Abrí la caja para anular.");
    }

    @Test
    @DisplayName("anular con la caja de ayer: CAJA_DE_OTRO_DIA con la fecha dd/MM de esa caja")
    void anularConCajaDeOtroDia() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 11, 8, 0))));

        CobranzaRechazadaException e = rechazoDeAnulacion();

        assertThat(e.motivo()).isEqualTo(CobranzaRechazadaException.Motivo.CAJA_DE_OTRO_DIA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de anular.");
    }

    @Test
    @DisplayName("anular con la caja abierta hoy devuelve su id y usa la misma caja compartida (FOR SHARE) que vender y cobrar")
    void anularConCajaDeHoy() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 12, 8, 0))));

        assertThat(service.cajaParaAnular()).isEqualTo(40L);
        assertThat(service.cajaParaCobrar()).isEqualTo(40L);
        assertThat(service.cajaParaVender()).isEqualTo(40L);
        verify(cajas, org.mockito.Mockito.times(3)).abiertaParaVenderScoped();
        verify(cajas, never()).abiertaScoped();
    }

    @Test
    @DisplayName("regresión: cobrar y vender conservan sus mensajes aunque anular tenga el suyo")
    void cobrarYVenderConservanSusMensajes() {
        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.empty());
        assertThat(rechazoDeCobro().getMessage()).isEqualTo("Abrí la caja para cobrar.");
        assertThat(rechazo().getMessage()).isEqualTo("Abrí la caja para vender.");
        assertThat(rechazoDeAnulacion().getMessage()).isEqualTo("Abrí la caja para anular.");

        when(cajas.abiertaParaVenderScoped()).thenReturn(Optional.of(abiertaEl(LocalDateTime.of(2026, 10, 11, 8, 0))));
        assertThat(rechazoDeCobro().getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de cobrar.");
        assertThat(rechazo().getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de vender.");
        assertThat(rechazoDeAnulacion().getMessage()).isEqualTo("La caja del 11/10 sigue abierta. Cerrala antes de anular.");
    }

    @Test
    @DisplayName("cajaParaAnular exige transacción (MANDATORY): el lock compartido vive hasta el commit de la anulación")
    void anularExigeTransaccion() throws NoSuchMethodException {
        Method metodo = CajaService.class.getMethod("cajaParaAnular");
        assertThat(metodo.getAnnotation(Transactional.class)).isNotNull();
        assertThat(metodo.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
