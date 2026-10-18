package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

    CajaService service;

    @BeforeEach
    void preparar() {
        service = new CajaService(cajas, movimientos, pagos, RELOJ);
    }

    private static Caja abiertaEl(LocalDateTime apertura) {
        Caja caja = Caja.abrir(3L, 9L, BigDecimal.ZERO, null, apertura);
        ReflectionTestUtils.setField(caja, "id", 40L);
        return caja;
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
}
