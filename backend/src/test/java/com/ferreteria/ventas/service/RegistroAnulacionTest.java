package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AnulacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoAnulacion;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ReciboRepository;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCT3B1-309 (CC-056, CC-057, CC-058, CC-059, CC-060, CC-062, CC-065, CC-066, CC-085): la secuencia de la anulación dentro de UNA
 * transacción, con colaboradores simulados. Lo que más importa es el ORDEN (caja S → cliente X → recién ahí el primer SELECT plano):
 * el recibo se lee después de tomar al cliente, así la foto REPEATABLE READ ve cualquier anulación previa que haya confirmado.
 */
@ExtendWith(MockitoExtension.class)
class RegistroAnulacionTest {

    static final Long FERRETERIA = 3L;
    static final Long COBRADOR = 2L;
    static final Long ANULADOR = 9L;
    static final Long CAJA_DE_HOY = 77L;
    static final Long CAJA_DEL_COBRO = 41L;
    static final Long CLIENTE = 5L;
    static final Long RECIBO_ID = 501L;
    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final String NOMBRE_CLIENTE = "Ferretería Norte SRL";
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 20, 12, 30);

    @Mock
    CajaService cajaService;

    @Mock
    ClienteDelDocumento clienteDelDocumento;

    @Mock
    ReciboRepository reciboRepository;

    @Mock
    CuentaCorrienteParaCobranza cuentaCorriente;

    @Mock
    CajaRepository cajaRepository;

    RegistroAnulacion registro;

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        registro = new RegistroAnulacion(cajaService, clienteDelDocumento, reciboRepository, cuentaCorriente, cajaRepository, RELOJ);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Recibo recibo(long clienteId, String total) {
        Recibo recibo = Recibo.emitir(FERRETERIA, 14L, CLAVE, clienteId, NOMBRE_CLIENTE, CAJA_DEL_COBRO, COBRADOR,
                LocalDateTime.of(2026, 10, 19, 9, 0), MedioPago.EFECTIVO, bd(total));
        ReflectionTestUtils.setField(recibo, "id", RECIBO_ID);
        return recibo;
    }

    private static Recibo reciboAnulado(String total) {
        Recibo recibo = recibo(CLIENTE, total);
        recibo.anular(ANULADOR, LocalDateTime.of(2026, 10, 19, 17, 45), 60L, "Cliente equivocado");
        return recibo;
    }

    /** Camino feliz armado: caja de hoy, cliente activo, recibo EMITIDO de 1.000 con dos imputaciones originales. */
    private void caminoFeliz() {
        lenient().when(cajaService.cajaParaAnular()).thenReturn(CAJA_DE_HOY);
        lenient().when(clienteDelDocumento.resolverParaAnulacion(CLIENTE))
                .thenReturn(new ClienteDelDocumento.Elegido(CLIENTE, NOMBRE_CLIENTE));
        lenient().when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(recibo(CLIENTE, "1000.00")));
        lenient().when(reciboRepository.saveAndFlush(any(Recibo.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cuentaCorriente.asentarAnulacion(any())).thenReturn(new AnulacionAsentada(900L, List.of(8L, 9L)));
        lenient().when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(
                new ImputacionAsentada(8L, "Venta N.º 8", bd("600.00")), new ImputacionAsentada(9L, "Venta N.º 9", bd("400.00"))));
        lenient().when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(COBRADOR, "Marta Cobradora", ANULADOR, "Ana Dueña"));
    }

    // ---- validarMotivo (CC-056, S-1): puro, sin base ----

    @Test
    @DisplayName("el motivo se recorta: «  Cobro mal cargado  » queda «Cobro mal cargado»; «12345» vale (≥ 5 caracteres, no se exigen letras)")
    void motivoValido() {
        assertThat(RegistroAnulacion.validarMotivo("  Cobro mal cargado  ")).isEqualTo("Cobro mal cargado");
        assertThat(RegistroAnulacion.validarMotivo("12345")).isEqualTo("12345");
        assertThat(RegistroAnulacion.validarMotivo("a".repeat(5))).hasSize(5);
        assertThat(RegistroAnulacion.validarMotivo("a".repeat(200))).hasSize(200);
        assertThat(RegistroAnulacion.validarMotivo("  " + "b".repeat(200) + "  ")).hasSize(200);
    }

    @Test
    @DisplayName("motivo «abc», en blanco, nulo, de 4 caracteres tras el trim: 400 con la clave motivo y el texto de CC-056")
    void motivoCorto() {
        for (String motivo : new String[] {"abc", "    ", "", null, "  abcd  "}) {
            assertThatThrownBy(() -> RegistroAnulacion.validarMotivo(motivo))
                    .as("motivo=%s", motivo)
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsExactly(Map.entry("motivo", "Escribí el motivo de la anulación (al menos 5 letras).")));
        }
    }

    @Test
    @DisplayName("motivo de 201 caracteres: 400 «El motivo admite hasta 200 caracteres.»")
    void motivoLargo() {
        assertThatThrownBy(() -> RegistroAnulacion.validarMotivo("x".repeat(201)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsExactly(Map.entry("motivo", "El motivo admite hasta 200 caracteres.")));
    }

    // ---- registrar: el orden de locks y las validaciones ----

    @Test
    @DisplayName("camino feliz: cajaParaAnular → resolverParaAnulacion → findScoped → saveAndFlush → asentarAnulacion, en ese orden estricto")
    void ordenEstricto() {
        caminoFeliz();

        CobroRegistrado cobro = registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR);

        InOrder orden = inOrder(cajaService, clienteDelDocumento, reciboRepository, cuentaCorriente);
        orden.verify(cajaService).cajaParaAnular();
        orden.verify(clienteDelDocumento).resolverParaAnulacion(CLIENTE);
        orden.verify(reciboRepository).findScoped(RECIBO_ID);
        orden.verify(reciboRepository).saveAndFlush(any(Recibo.class));
        orden.verify(cuentaCorriente).asentarAnulacion(any());
        assertThat(cobro.repetida()).isFalse();
        assertThat(cobro.recibo().isAnulado()).isTrue();
    }

    @Test
    @DisplayName("NADA se toca antes de tomar al cliente salvo la caja: ni recibo, ni puerto, ni usuarios (snapshot REPEATABLE READ)")
    void nadaAntesDelCliente() {
        caminoFeliz();
        doAnswer(inv -> {
            verifyNoInteractions(reciboRepository, cuentaCorriente, cajaRepository);
            return new ClienteDelDocumento.Elegido(CLIENTE, NOMBRE_CLIENTE);
        }).when(clienteDelDocumento).resolverParaAnulacion(CLIENTE);

        registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR);

        verify(reciboRepository).findScoped(RECIBO_ID); // y después sí se lee
    }

    @Test
    @DisplayName("el asiento lleva total, número, motivo recortado y usuario; el recibo queda ANULADO con quién, cuándo (reloj BA), caja de hoy y motivo")
    void asientoYAuditoria() {
        caminoFeliz();

        CobroRegistrado cobro = registro.registrar(RECIBO_ID, CLIENTE, "  Cobro mal cargado  ", ANULADOR);

        ArgumentCaptor<AsientoAnulacion> asiento = ArgumentCaptor.forClass(AsientoAnulacion.class);
        verify(cuentaCorriente).asentarAnulacion(asiento.capture());
        assertThat(asiento.getValue()).isEqualTo(new AsientoAnulacion(CLIENTE, RECIBO_ID, 14L, bd("1000.00"), "Cobro mal cargado", ANULADOR));
        Recibo recibo = cobro.recibo();
        assertThat(recibo.getEstado().name()).isEqualTo("ANULADO");
        assertThat(recibo.getFechaAnulacion()).isEqualTo(AHORA);
        assertThat(recibo.getUsuarioAnulacionId()).isEqualTo(ANULADOR);
        assertThat(recibo.getCajaAnulacionId()).isEqualTo(CAJA_DE_HOY); // no la caja del cobro (CAJA_DEL_COBRO)
        assertThat(recibo.getMotivoAnulacion()).isEqualTo("Cobro mal cargado");
        assertThat(cobro.imputaciones()).extracting(ImputacionAsentada::comprobanteId).containsExactly(8L, 9L);
        assertThat(cobro.cobradoPor()).isEqualTo("Marta Cobradora");
        assertThat(cobro.anuladoPor()).isEqualTo("Ana Dueña");
    }

    @Test
    @DisplayName("recibo inexistente o de otra ferretería (findScoped vacío): 404 «Recibo no encontrado», sin marcar ni asentar")
    void reciboInexistente() {
        caminoFeliz();
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recibo no encontrado");
        verify(reciboRepository, never()).saveAndFlush(any());
        verify(cuentaCorriente, never()).asentarAnulacion(any());
    }

    @Test
    @DisplayName("el recibo no es del cliente que se tomó: IllegalStateException (no pasa por el flujo normal), sin escribir")
    void clienteDistinto() {
        caminoFeliz();
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(recibo(CLIENTE + 1, "1000.00")));

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOf(IllegalStateException.class);
        verify(reciboRepository, never()).saveAndFlush(any());
        verify(cuentaCorriente, never()).asentarAnulacion(any());
    }

    @Test
    @DisplayName("recibo ya anulado: 409 RECIBO_YA_ANULADO con quién y cuándo, sin volver a marcar ni asentar")
    void yaAnulado() {
        caminoFeliz();
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(reciboAnulado("1000.00")));

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
                    assertThat(e.motivo().estadoHttp()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Este recibo ya fue anulado por Ana Dueña el 19/10/2026 17:45.");
                    assertThat(e.anuladoPor()).isEqualTo("Ana Dueña");
                    assertThat(e.fechaAnulacion()).isEqualTo(LocalDateTime.of(2026, 10, 19, 17, 45));
                });
        verify(reciboRepository, never()).saveAndFlush(any());
        verify(cuentaCorriente, never()).asentarAnulacion(any());
    }

    @Test
    @DisplayName("caja cerrada o de otro día (422): no se llama al cliente, ni se lee el recibo, ni se asienta; la caja gana al estado")
    void cajaGanaAlEstado() {
        when(cajaService.cajaParaAnular()).thenThrow(new CobranzaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para anular."));

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
                    assertThat(e.getMessage()).isEqualTo("Abrí la caja para anular.");
                });
        verifyNoInteractions(clienteDelDocumento, reciboRepository, cuentaCorriente, cajaRepository);
    }

    @Test
    @DisplayName("cliente desactivado (422): el recibo ni se lee, y nada se asienta")
    void clienteDesactivado() {
        lenient().when(cajaService.cajaParaAnular()).thenReturn(CAJA_DE_HOY);
        when(clienteDelDocumento.resolverParaAnulacion(CLIENTE)).thenThrow(new ReglaDeNegocioException("El cliente está desactivado."));

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
        verifyNoInteractions(reciboRepository, cuentaCorriente, cajaRepository);
    }

    @Test
    @DisplayName("si el puerto rechaza el asiento (IllegalStateException) la excepción sube: la transacción entera se revierte")
    void elPuertoRechaza() {
        caminoFeliz();
        when(cuentaCorriente.asentarAnulacion(any())).thenThrow(new IllegalStateException("sin COBRO"));

        assertThatThrownBy(() -> registro.registrar(RECIBO_ID, CLIENTE, "Cobro mal cargado", ANULADOR))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- pre-resolución y 409 del duplicado ----

    @Test
    @DisplayName("clienteDelRecibo devuelve el cliente por clienteDeScoped (sin locks); inexistente o ajeno es 404")
    void clienteDelRecibo() {
        when(reciboRepository.clienteDeScoped(RECIBO_ID)).thenReturn(Optional.of(CLIENTE));
        when(reciboRepository.clienteDeScoped(999L)).thenReturn(Optional.empty());

        assertThat(registro.clienteDelRecibo(RECIBO_ID)).isEqualTo(CLIENTE);
        assertThatThrownBy(() -> registro.clienteDelRecibo(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recibo no encontrado");
        verifyNoInteractions(cajaService, clienteDelDocumento, cuentaCorriente);
    }

    @Test
    @DisplayName("rechazoSiAnulado: con el recibo anulado lanza el 409 con quién y cuándo; EMITIDO o inexistente vuelve sin error")
    void rechazoSiAnulado() {
        lenient().when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(ANULADOR, "Ana Dueña"));
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(reciboAnulado("1000.00")));
        when(reciboRepository.findScoped(502L)).thenReturn(Optional.of(recibo(CLIENTE, "50.00")));
        when(reciboRepository.findScoped(503L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registro.rechazoSiAnulado(RECIBO_ID))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
                    assertThat(e.getMessage()).isEqualTo("Este recibo ya fue anulado por Ana Dueña el 19/10/2026 17:45.");
                });
        assertThatCode(() -> registro.rechazoSiAnulado(502L)).doesNotThrowAnyException();
        assertThatCode(() -> registro.rechazoSiAnulado(503L)).doesNotThrowAnyException();
    }

    // ---- transacciones ----

    @Test
    @DisplayName("registrar es @Transactional de escritura (una sola transacción); la pre-resolución y rechazoSiAnulado son de solo lectura")
    void transacciones() throws NoSuchMethodException {
        Method registrar = RegistroAnulacion.class.getMethod("registrar", Long.class, Long.class, String.class, Long.class);
        Method cliente = RegistroAnulacion.class.getMethod("clienteDelRecibo", Long.class);
        Method rechazo = RegistroAnulacion.class.getMethod("rechazoSiAnulado", Long.class);

        assertThat(registrar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(registrar.getAnnotation(Transactional.class).readOnly()).isFalse();
        assertThat(cliente.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(rechazo.getAnnotation(Transactional.class).readOnly()).isTrue();
    }
}
