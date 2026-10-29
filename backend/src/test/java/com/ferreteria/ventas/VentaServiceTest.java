package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EmisorDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorDelDocumento;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.RegistroVenta;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** La fachada sin transaccion: reintenta deadlocks y resuelve la carrera de la clave (C-009). */
@ExtendWith(MockitoExtension.class)
class VentaServiceTest {

    static final Long USUARIO = 9L;

    @Mock
    RegistroVenta registroVenta;

    @Mock
    FacturacionParaVenta facturacion;

    VentaService service;

    ConfirmarVenta pedido = new ConfirmarVenta("7c9e6679-7425-40de-944b-e07fc1f90ae7",
            List.of(new Renglon(1L, BigDecimal.ONE, new BigDecimal("10.00"))), new BigDecimal("10.00"),
            1L);

    static final long COMPROBANTE = 31L;

    VentaRegistrada nueva = new VentaRegistrada(comprobante(true), List.of(), null, List.of(), false);
    VentaRegistrada repetida = new VentaRegistrada(comprobante(true), List.of(), null, List.of(), true);
    /** La venta nueva que dio de alta su registro fiscal en su transaccion. */
    VentaRegistrada nuevaConRegistro = new VentaRegistrada(comprobante(true), List.of(), null, List.of(), false, true, null,
            null);

    static final EstadoFiscalVenta AUTORIZADA = new EstadoFiscalVenta("AUTORIZADO", "FACTURA", "B", 1, 7L,
            "74123456789012", LocalDate.of(2026, 11, 7), "SIMULADOR");

    /** El documento X de esa venta, con su estado de la misma lectura. */
    static final DocumentoFiscal DOCUMENTO = new DocumentoFiscal(AUTORIZADA, false, "X", false, null,
            "SIMULADO — sin validez fiscal", null, null, LocalDate.of(2026, 10, 28), null, null,
            new EmisorDelDocumento("El Tornillo SRL", "30712345678", "IVA RESPONSABLE INSCRIPTO",
                    "Av. Luro 1234, Mar del Plata", "901-123456-7", LocalDate.of(2015, 3, 1)),
            new ReceptorDelDocumento(null, "CONSUMIDOR FINAL", null, null, null, true), new BigDecimal("12.10"),
            new BigDecimal("10.00"), new BigDecimal("2.10"), new BigDecimal("0.00"), new BigDecimal("0.00"), List.of(),
            null, null, null, null);

    @BeforeEach
    void preparar() {
        service = new VentaService(registroVenta, facturacion);
    }

    /** Una venta guardada con el id 31, facturable o no. */
    static Comprobante comprobante(boolean facturable) {
        Comprobante c = Comprobante.venta(3L, 12L, "7c9e6679-7425-40de-944b-e07fc1f90ae7", 9L,
                TotalesVenta.de(List.of(RenglonVenta.de(new BigDecimal("10.00"), BigDecimal.ONE, new BigDecimal("21.00")))),
                77L, BigDecimal.ZERO, null, null, facturable, LocalDateTime.of(2026, 10, 28, 10, 15, 30));
        ReflectionTestUtils.setField(c, "id", COMPROBANTE);
        return c;
    }

    @Test
    @DisplayName("la fachada no tiene transaccion: cada intento abre la suya en RegistroVenta")
    void sinTransaccion() throws NoSuchMethodException {
        assertThat(VentaService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VentaService.class.getMethod("cobrar", ConfirmarVenta.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VentaService.class.getMethod("cobrar", ConfirmarVenta.class, Long.class, boolean.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("una venta que sale bien se devuelve tal cual, con un solo intento")
    void caminoFeliz() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un deadlock se reintenta y la venta sale en el segundo intento")
    void reintentaUnDeadlock() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(2)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("tres deadlocks o timeouts seguidos: 3 intentos y despues avisa que el sistema esta ocupado (503), con el ultimo error como causa")
    void tresIntentosYSistemaOcupado() {
        CannotAcquireLockException ultimo = new CannotAcquireLockException("deadlock");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(ultimo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isInstanceOf(SistemaOcupadoException.class)
                .hasCause(ultimo).hasMessage("El sistema está ocupado, reintentá.");
        verify(registroVenta, times(3)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un error que no es de lock se relanza tal cual, sin reintentos y sin disfrazarlo de sistema ocupado (sigue siendo 500)")
    void otroErrorNoEsSistemaOcupado() {
        IllegalStateException otro = new IllegalStateException("otra cosa");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(otro);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otro);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un rechazo de la venta no se reintenta")
    void rechazoNoSeReintenta() {
        VentaRechazadaException rechazo = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambio", List.of());
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(rechazo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(rechazo);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("perder la carrera de la clave: busca la venta por clave y la devuelve repetida")
    void carreraDeClave() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(new DataIntegrityViolationException("uk_comprobante_clave"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("una violacion de integridad sin venta con esa clave se relanza")
    void integridadSinVenta() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("otra restriccion");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(error);
        when(registroVenta.repetida(pedido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) no es una carrera de clave: se relanza sin buscar la venta")
    void fueraDeRangoNoEsCarrera() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException("Out of range value for column 'total'", "22003", 1264));
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(error);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
        verify(registroVenta, never()).repetida(any());
    }

    @Test
    @DisplayName("la carrera con otro contenido propaga el 422 de la busqueda por clave")
    void carreraConOtroContenido() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(new DataIntegrityViolationException("uk"));
        VentaRechazadaException otroContenido = new VentaRechazadaException(
                Motivo.CLAVE_CON_OTRO_CONTENIDO, "Esta venta ya se registró con otros datos", List.of());
        when(registroVenta.repetida(pedido)).thenThrow(otroContenido);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otroContenido);
    }

    @Test
    @DisplayName("un deadlock y despues la carrera de clave: reintenta y despues resuelve por clave")
    void deadlockYLuegoCarrera() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(new DataIntegrityViolationException("uk"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(2)).registrar(eq(pedido), any(), eq(false));
    }

    // ---------------------------------------------------------------- cuenta corriente (CC-005, CC-009)

    static VentaRechazadaException limite() {
        return VentaRechazadaException.limiteCuentaCorriente(VentaRechazadaException.ExcesoLimite.sinCifras());
    }

    @Test
    @DisplayName("el rol del Dueño llega a RegistroVenta; la sobrecarga de 2 argumentos es como Vendedor")
    void rolDelUsuario() {
        when(registroVenta.registrar(pedido, USUARIO, true)).thenReturn(nueva);
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(repetida);

        assertThat(service.cobrar(pedido, USUARIO, true)).isSameAs(nueva);
        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
    }

    @Test
    @DisplayName("un rechazo por LIMITE con la clave ya registrada (el doble envio con limite justo) devuelve la venta original")
    void limiteConLaVentaYaRegistrada() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(limite());
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un rechazo por LIMITE sin venta con esa clave se relanza tal cual: es un rechazo de verdad")
    void limiteSinVentaPrevia() {
        VentaRechazadaException rechazo = limite();
        when(registroVenta.registrar(pedido, USUARIO, true)).thenThrow(rechazo);
        when(registroVenta.repetida(pedido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO, true)).isSameAs(rechazo);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, true);
    }

    @Test
    @DisplayName("un rechazo por LIMITE con la clave usada con otro contenido propaga CLAVE_CON_OTRO_CONTENIDO")
    void limiteConClaveDeOtroContenido() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(limite());
        VentaRechazadaException otroContenido = new VentaRechazadaException(
                Motivo.CLAVE_CON_OTRO_CONTENIDO, "Esta venta ya se registró con otros datos", List.of());
        when(registroVenta.repetida(pedido)).thenThrow(otroContenido);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otroContenido);
    }

    @Test
    @DisplayName("los demás rechazos (cuenta no habilitada, precios) NO buscan la venta repetida")
    void otrosMotivosNoDisparanElFallback() {
        VentaRechazadaException noHabilitada = VentaRechazadaException.cuentaNoHabilitada("Norte SRL");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(noHabilitada);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(noHabilitada);

        VentaRechazadaException precios = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambio", List.of());
        when(registroVenta.registrar(pedido, USUARIO, true)).thenThrow(precios);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO, true)).isSameAs(precios);
        verify(registroVenta, never()).repetida(any());
    }

    @Test
    @DisplayName("un deadlock y despues un rechazo por LIMITE con la venta ya registrada: reintenta y despues devuelve la original")
    void deadlockYLuegoLimite() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(limite());
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(2)).registrar(pedido, USUARIO, false);
    }

    // ---------------------------------------------------------------- CU-07 paso 5: el CAE despues del commit (S4)

    @Test
    @DisplayName("la venta nueva con registro fiscal pide el CAE una sola vez, despues de los reintentos, y responde con el documento leido despues (C4)")
    void pideElCaeDespuesDelBucle() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nuevaConRegistro);
        when(facturacion.autorizar(COMPROBANTE)).thenReturn(Optional.of(AUTORIZADA));
        when(facturacion.documento(COMPROBANTE)).thenReturn(Optional.of(DOCUMENTO));

        VentaRegistrada venta = service.cobrar(pedido, USUARIO);

        assertThat(venta.fiscal()).isEqualTo(DOCUMENTO);
        assertThat(venta.comprobante()).isSameAs(nuevaConRegistro.comprobante());
        InOrder orden = inOrder(facturacion);
        orden.verify(facturacion, times(1)).autorizar(COMPROBANTE);
        orden.verify(facturacion, times(1)).documento(COMPROBANTE);
        verify(facturacion, never()).estado(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("la repetida facturable (la misma clave, la carrera de la clave o CC-009) lee el estado y el documento actuales sin pedir otro CAE")
    void laRepetidaLeeElEstadoSinPedirOtroCae() {
        when(facturacion.estado(COMPROBANTE)).thenReturn(Optional.of(AUTORIZADA));
        when(facturacion.documento(COMPROBANTE)).thenReturn(Optional.of(DOCUMENTO));
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenReturn(repetida)
                .thenThrow(new DataIntegrityViolationException("uk_comprobante_clave"))
                .thenThrow(limite());
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        for (int i = 0; i < 3; i++) {
            VentaRegistrada venta = service.cobrar(pedido, USUARIO);
            assertThat(venta.repetida()).isTrue();
            assertThat(venta.fiscal()).isEqualTo(DOCUMENTO);
        }
        verify(facturacion, times(3)).estado(COMPROBANTE);
        verify(facturacion, times(3)).documento(COMPROBANTE);
        verify(facturacion, never()).autorizar(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("con estado pero sin documento (el registro no se pudo leer otra vez) la venta responde sin «fiscal»")
    void sinDocumentoNoHayFiscal() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nuevaConRegistro);
        when(facturacion.autorizar(COMPROBANTE)).thenReturn(Optional.of(AUTORIZADA));
        when(facturacion.documento(COMPROBANTE)).thenReturn(Optional.empty());

        assertThat(service.cobrar(pedido, USUARIO).fiscal()).isNull();
    }

    @Test
    @DisplayName("la venta sin registro fiscal y la repetida no facturable no le preguntan nada a facturacion")
    void sinRegistroNoPregunta() {
        VentaRegistrada repetidaNoFacturable = new VentaRegistrada(comprobante(false), List.of(), null, List.of(), true);
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nueva).thenReturn(repetidaNoFacturable);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetidaNoFacturable);
        org.mockito.Mockito.verifyNoInteractions(facturacion);
    }

    @Test
    @DisplayName("una falla al pedir el CAE o al leer el estado no bloquea la venta ya cobrada: responde sin «fiscal», nunca con un error")
    void unaFallaDeFacturacionNoBloqueaLaVenta() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nuevaConRegistro).thenReturn(repetida);
        when(facturacion.autorizar(COMPROBANTE)).thenThrow(new IllegalStateException("con una transaccion abierta"));
        when(facturacion.estado(COMPROBANTE)).thenThrow(new CannotAcquireLockException("sin conexion"));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nuevaConRegistro);
        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
    }

    @Test
    @DisplayName("una falla al leer el documento despues del CAE tampoco bloquea la venta: responde sin «fiscal»")
    void unaFallaAlLeerElDocumentoNoBloqueaLaVenta() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nuevaConRegistro);
        when(facturacion.autorizar(COMPROBANTE)).thenReturn(Optional.of(AUTORIZADA));
        when(facturacion.documento(COMPROBANTE)).thenThrow(new CannotAcquireLockException("sin conexion"));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nuevaConRegistro);
    }
}
