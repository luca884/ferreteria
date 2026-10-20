package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.NumeradorReciboRepository;
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
 * CCT3-309 (CC-027, CC-029, CC-033, CC-034, CC-035, CC-036, CC-037, CC-038, CC-041): la secuencia de la cobranza dentro de
 * UNA transacción, con colaboradores simulados. Lo que más importa acá es el ORDEN (requisito firme de Luca): primero la
 * caja (S) y el cliente (X), recién después cualquier lectura plana; el numerador, el recibo y el asiento van detrás.
 */
@ExtendWith(MockitoExtension.class)
class RegistroCobranzaTest {

    static final Long FERRETERIA = 3L;
    static final Long USUARIO = 9L;
    static final Long CAJA = 77L;
    static final Long CLIENTE = 5L;
    static final Long RECIBO_ID = 501L;
    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final String NOMBRE_CLIENTE = "Ferretería Norte SRL";
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-19T15:30:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));

    @Mock
    CajaService cajaService;

    @Mock
    ClienteDelDocumento clienteDelDocumento;

    @Mock
    NumeradorReciboRepository numeradorRepository;

    @Mock
    ReciboRepository reciboRepository;

    @Mock
    CuentaCorrienteParaCobranza cuentaCorriente;

    @Mock
    CajaRepository cajaRepository;

    RegistroCobranza registro;

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        registro = new RegistroCobranza(cajaService, clienteDelDocumento, numeradorRepository, reciboRepository,
                cuentaCorriente, cajaRepository, RELOJ);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Imputar imputar(long comprobanteId, String importe) {
        return new Imputar(comprobanteId, bd(importe));
    }

    private static ImputacionAsentada asentada(long comprobanteId, String importe) {
        return new ImputacionAsentada(comprobanteId, "Venta N.º " + comprobanteId, bd(importe));
    }

    private static CobranzaPedido pedido(MedioPago medio, String total, Imputar... imputaciones) {
        return new CobranzaPedido(CLAVE, CLIENTE, medio, bd(total), List.of(imputaciones));
    }

    private static Recibo reciboGuardado(long numero, long clienteId, MedioPago medio, String total) {
        Recibo recibo = Recibo.emitir(FERRETERIA, numero, CLAVE, clienteId, NOMBRE_CLIENTE, CAJA, 2L,
                LocalDateTime.of(2026, 10, 19, 9, 0), medio, bd(total));
        ReflectionTestUtils.setField(recibo, "id", RECIBO_ID);
        return recibo;
    }

    /** El camino feliz armado: caja, cliente activo, número 12, el recibo guardado con id y el nombre del cobrador. */
    private void caminoFeliz(List<ImputacionAsentada> asentadas) {
        lenient().when(cajaService.cajaParaCobrar()).thenReturn(CAJA);
        lenient().when(clienteDelDocumento.resolverParaCobranza(CLIENTE))
                .thenReturn(new ClienteDelDocumento.Elegido(CLIENTE, NOMBRE_CLIENTE));
        lenient().when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        lenient().when(numeradorRepository.siguienteScoped()).thenReturn(12L);
        lenient().when(reciboRepository.save(any(Recibo.class))).thenAnswer(inv -> {
            Recibo recibo = inv.getArgument(0);
            ReflectionTestUtils.setField(recibo, "id", RECIBO_ID);
            return recibo;
        });
        lenient().when(cuentaCorriente.asentarCobro(any())).thenReturn(asentadas);
        lenient().when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(USUARIO, "Marta Dueña"));
    }

    private void nadaTocado() {
        verifyNoInteractions(cajaService, clienteDelDocumento, numeradorRepository, reciboRepository, cuentaCorriente,
                cajaRepository);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Forma (400 por campo, antes de tocar nada)
    // ---------------------------------------------------------------------------------------------------------------

    private Map<String, String> erroresDe(CobranzaPedido pedido) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> registro.registrar(pedido, USUARIO), DatosInvalidosException.class).errors();
    }

    @Test
    @DisplayName("total ≤ 0, nulo, con más de 2 decimales o sobre el tope: 400 en total, sin tocar nada")
    void totalInvalido() {
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "0"))).containsKey("total");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "-5"))).containsKey("total");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100.001")).get("total")).isEqualTo("El total admite hasta 2 decimales.");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "10000000000000.00")).get("total"))
                .isEqualTo("El total supera el máximo permitido.");
        assertThat(erroresDe(new CobranzaPedido(CLAVE, CLIENTE, MedioPago.EFECTIVO, null, List.of()))).containsKey("total");
        nadaTocado();
    }

    @Test
    @DisplayName("100.10 y 100.1000 con ceros de más son válidos (la escala se mide sin ceros finales)")
    void ceroDeMasNoSobra() {
        caminoFeliz(List.of());

        CobroRegistrado cobro = registro.registrar(pedido(MedioPago.EFECTIVO, "100.1000"), USUARIO);

        assertThat(cobro.repetida()).isFalse();
    }

    @Test
    @DisplayName("un medio que no es efectivo ni transferencia (o ninguno) es 400 «Elegí efectivo o transferencia»")
    void medioInvalido() {
        assertThat(erroresDe(pedido(MedioPago.CUENTA_CORRIENTE, "10")).get("medio")).isEqualTo("Elegí efectivo o transferencia");
        assertThat(erroresDe(pedido(MedioPago.DEBITO, "10")).get("medio")).isEqualTo("Elegí efectivo o transferencia");
        assertThat(erroresDe(pedido(null, "10")).get("medio")).isEqualTo("Elegí efectivo o transferencia");
        nadaTocado();
    }

    @Test
    @DisplayName("una clave ausente o que no es UUID es 400 en clave")
    void claveInvalida() {
        assertThat(erroresDe(new CobranzaPedido("no-es-uuid", CLIENTE, MedioPago.EFECTIVO, bd("10"), List.of())))
                .containsKey("clave");
        assertThat(erroresDe(new CobranzaPedido(null, CLIENTE, MedioPago.EFECTIVO, bd("10"), List.of())))
                .containsKey("clave");
        nadaTocado();
    }

    @Test
    @DisplayName("sin cliente es 400 en clienteId")
    void sinCliente() {
        assertThat(erroresDe(new CobranzaPedido(CLAVE, null, MedioPago.EFECTIVO, bd("10"), List.of())))
                .containsKey("clienteId");
        nadaTocado();
    }

    @Test
    @DisplayName("renglón con importe ≤ 0, nulo, con más de 2 decimales o sin comprobante: 400 en ese renglón")
    void renglonInvalido() {
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", imputar(1, "0"))))
                .containsEntry("imputaciones[0].importe", "El importe tiene que ser mayor que cero.");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", imputar(1, "10"), imputar(2, "-1"))))
                .containsKey("imputaciones[1].importe").doesNotContainKey("imputaciones[0].importe");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", imputar(1, "1.001"))))
                .containsEntry("imputaciones[0].importe", "El importe admite hasta 2 decimales.");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", new Imputar(null, bd("10")))))
                .containsKey("imputaciones[0].comprobanteId");
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", new Imputar(1L, null))))
                .containsKey("imputaciones[0].importe");
        nadaTocado();
    }

    @Test
    @DisplayName("dos renglones al mismo comprobante: 400 «Cada comprobante va una sola vez.»")
    void comprobanteRepetido() {
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", imputar(1, "10"), imputar(1, "20"))).get("imputaciones"))
                .isEqualTo("Cada comprobante va una sola vez.");
        nadaTocado();
    }

    @Test
    @DisplayName("lo imputado por encima del total: 400 «Lo imputado supera el total del recibo.»; igual al total es válido")
    void sumaMayorQueElTotal() {
        assertThat(erroresDe(pedido(MedioPago.EFECTIVO, "100", imputar(1, "60"), imputar(2, "40.01"))).get("imputaciones"))
                .isEqualTo("Lo imputado supera el total del recibo.");
        nadaTocado();

        caminoFeliz(List.of(asentada(1, "60"), asentada(2, "40")));
        assertThat(registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(1, "60"), imputar(2, "40")), USUARIO).repetida())
                .isFalse();
    }

    @Test
    @DisplayName("los errores de forma salen JUNTOS, uno por campo")
    void erroresJuntos() {
        Map<String, String> errores = erroresDe(new CobranzaPedido("x", null, MedioPago.CUENTA_CORRIENTE, bd("-1"),
                List.of(imputar(1, "0"), imputar(1, "5"))));

        assertThat(errores).containsKeys("clave", "clienteId", "medio", "total", "imputaciones[0].importe", "imputaciones");
        nadaTocado();
    }

    @Test
    @DisplayName("sin imputaciones es válido: todo queda «a cuenta»")
    void imputacionesVaciasEsValido() {
        caminoFeliz(List.of());

        assertThat(registro.registrar(pedido(MedioPago.TRANSFERENCIA, "10"), USUARIO).imputaciones()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Orden de locks y de lecturas (requisito firme)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ORDEN: cajaParaCobrar → resolverParaCobranza → repetida → numerador → save(recibo) → asentarCobro")
    void ordenEstricto() {
        caminoFeliz(List.of(asentada(1, "100")));

        registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(1, "100")), USUARIO);

        InOrder orden = inOrder(cajaService, clienteDelDocumento, reciboRepository, numeradorRepository, cuentaCorriente,
                cajaRepository);
        orden.verify(cajaService).cajaParaCobrar();
        orden.verify(clienteDelDocumento).resolverParaCobranza(CLIENTE);
        orden.verify(reciboRepository).porClaveScoped(CLAVE);
        orden.verify(numeradorRepository).siguienteScoped();
        orden.verify(reciboRepository).save(any(Recibo.class));
        orden.verify(cuentaCorriente).asentarCobro(any(AsientoCobro.class));
        orden.verify(cajaRepository).nombresScoped(any());
        orden.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("REQUISITO FIRME: ningún repositorio ni el puerto se tocan ANTES de tomar al cliente (snapshot REPEATABLE READ)")
    void nadaPlanoAntesDelCliente() {
        caminoFeliz(List.of());
        // En el instante exacto en que se toma al cliente, ni un SELECT plano (recibos, imputaciones, nombres) ni el numerador
        // pueden haber corrido: solo la caja (lectura bloqueante FOR SHARE) puede ir antes.
        doAnswer(inv -> {
            verifyNoInteractions(reciboRepository, numeradorRepository, cuentaCorriente, cajaRepository);
            return new ClienteDelDocumento.Elegido(CLIENTE, NOMBRE_CLIENTE);
        }).when(clienteDelDocumento).resolverParaCobranza(CLIENTE);

        registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO);

        verify(clienteDelDocumento).resolverParaCobranza(CLIENTE);
        verify(reciboRepository).porClaveScoped(CLAVE);
    }

    @Test
    @DisplayName("la caja se toma primero: antes de ella no se toca el cliente ni ningún repositorio")
    void cajaPrimero() {
        caminoFeliz(List.of());
        doAnswer(inv -> {
            verifyNoInteractions(clienteDelDocumento, reciboRepository, numeradorRepository, cuentaCorriente, cajaRepository);
            return CAJA;
        }).when(cajaService).cajaParaCobrar();

        registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO);

        verify(cajaService).cajaParaCobrar();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El recibo y el asiento
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("el recibo sale con número, clave, nombre copiado del cliente, caja, usuario, hora del reloj, medio y total")
    void reciboEmitido() {
        caminoFeliz(List.of());

        registro.registrar(pedido(MedioPago.TRANSFERENCIA, "250.50"), USUARIO);

        ArgumentCaptor<Recibo> captor = ArgumentCaptor.forClass(Recibo.class);
        verify(reciboRepository).save(captor.capture());
        Recibo recibo = captor.getValue();
        assertThat(recibo.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(recibo.getNumero()).isEqualTo(12L);
        assertThat(recibo.getClaveIdempotencia()).isEqualTo(CLAVE);
        assertThat(recibo.getClienteId()).isEqualTo(CLIENTE);
        assertThat(recibo.getClienteNombre()).isEqualTo(NOMBRE_CLIENTE);
        assertThat(recibo.getCajaId()).isEqualTo(CAJA);
        assertThat(recibo.getUsuarioId()).isEqualTo(USUARIO);
        assertThat(recibo.getFecha()).isEqualTo(LocalDateTime.of(2026, 10, 19, 12, 30));
        assertThat(recibo.getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(recibo.getTotal()).isEqualByComparingTo("250.50");
    }

    @Test
    @DisplayName("el asiento lleva cliente, id y número del recibo, total, usuario e imputaciones tal como se pidieron")
    void asientoPedido() {
        caminoFeliz(List.of(asentada(1, "30"), asentada(2, "70")));

        registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(2, "70"), imputar(1, "30")), USUARIO);

        ArgumentCaptor<AsientoCobro> captor = ArgumentCaptor.forClass(AsientoCobro.class);
        verify(cuentaCorriente).asentarCobro(captor.capture());
        AsientoCobro asiento = captor.getValue();
        assertThat(asiento.clienteId()).isEqualTo(CLIENTE);
        assertThat(asiento.reciboId()).isEqualTo(RECIBO_ID);
        assertThat(asiento.reciboNumero()).isEqualTo(12L);
        assertThat(asiento.total()).isEqualByComparingTo("100");
        assertThat(asiento.usuarioId()).isEqualTo(USUARIO);
        assertThat(asiento.imputaciones()).containsExactly(imputar(2, "70"), imputar(1, "30"));
    }

    @Test
    @DisplayName("devuelve el recibo, lo asentado, el nombre de quien cobró y repetida=false; el excedente «a cuenta» se deriva")
    void resultado() {
        caminoFeliz(List.of(asentada(1, "60")));

        CobroRegistrado cobro = registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(1, "60")), USUARIO);

        assertThat(cobro.repetida()).isFalse();
        assertThat(cobro.cobradoPor()).isEqualTo("Marta Dueña");
        assertThat(cobro.imputaciones()).containsExactly(asentada(1, "60"));
        assertThat(cobro.recibo().getNumero()).isEqualTo(12L);
        assertThat(cobro.aCuenta()).isEqualByComparingTo("40");
        verify(cajaRepository).nombresScoped(java.util.Set.of(USUARIO));
    }

    @Test
    @DisplayName("ADELANTO (CC-027): cliente activo sin saldo ni pendientes ni límite resuelve, numera, guarda el recibo y asienta con imputaciones vacías")
    void adelanto() {
        caminoFeliz(List.of());

        CobroRegistrado cobro = registro.registrar(pedido(MedioPago.EFECTIVO, "500"), USUARIO);

        ArgumentCaptor<AsientoCobro> captor = ArgumentCaptor.forClass(AsientoCobro.class);
        verify(cuentaCorriente).asentarCobro(captor.capture());
        assertThat(captor.getValue().imputaciones()).isEmpty();
        assertThat(captor.getValue().total()).isEqualByComparingTo("500");
        assertThat(cobro.aCuenta()).isEqualByComparingTo("500");
        verify(numeradorRepository).siguienteScoped();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rechazos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("sin caja (o de otro día) la cobranza se rechaza sin llamar a cliente, numerador ni asiento")
    void rechazoDeCaja() {
        when(cajaService.cajaParaCobrar())
                .thenThrow(new CobranzaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para cobrar."));

        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
                    assertThat(e.getMessage()).isEqualTo("Abrí la caja para cobrar.");
                });

        verifyNoInteractions(clienteDelDocumento, numeradorRepository, reciboRepository, cuentaCorriente, cajaRepository);
    }

    @Test
    @DisplayName("la caja de otro día también corta antes del cliente")
    void rechazoDeCajaDeOtroDia() {
        when(cajaService.cajaParaCobrar()).thenThrow(new CobranzaRechazadaException(Motivo.CAJA_DE_OTRO_DIA, "La caja del 18/10 sigue abierta."));

        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.CAJA_DE_OTRO_DIA));

        verifyNoInteractions(clienteDelDocumento, numeradorRepository, reciboRepository, cuentaCorriente);
    }

    @Test
    @DisplayName("un cliente desactivado (422) o ajeno (404) corta ANTES de leer recibos y de numerar")
    void clienteNoCobrable() {
        when(cajaService.cajaParaCobrar()).thenReturn(CAJA);
        when(clienteDelDocumento.resolverParaCobranza(CLIENTE)).thenThrow(new ReglaDeNegocioException("El cliente está desactivado."));

        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO))
                .isInstanceOf(ReglaDeNegocioException.class).hasMessage("El cliente está desactivado.");

        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Cliente no encontrado"))
                .when(clienteDelDocumento).resolverParaCobranza(CLIENTE);
        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(numeradorRepository, reciboRepository, cuentaCorriente);
    }

    @Test
    @DisplayName("ImputacionInvalida del puerto es IMPUTACION_EXCEDE_DEUDA (409) con las deudas actuales")
    void excedeDeuda() {
        caminoFeliz(List.of());
        List<DeudaActual> deudas = List.of(new DeudaActual(1L, "Venta N.º 1", bd("0")), new DeudaActual(2L, "Venta N.º 2", bd("20.00")));
        when(cuentaCorriente.asentarCobro(any()))
                .thenThrow(new ImputacionInvalidaException(ImputacionInvalidaException.Motivo.EXCEDE_DEUDA, deudas));

        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(1, "50"), imputar(2, "50")), USUARIO))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
                    assertThat(e.motivo().estadoHttp()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.");
                    assertThat(e.deudas()).isEqualTo(deudas);
                });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Idempotencia (repetida)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("misma clave y mismo contenido: devuelve el recibo original (repetida=true) sin numerar ni asentar; el orden de imputaciones no importa")
    void repetidaMismoContenido() {
        caminoFeliz(List.of());
        Recibo existente = reciboGuardado(7, CLIENTE, MedioPago.EFECTIVO, "100.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(existente));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(1, "70.00"), asentada(2, "30.00")));
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Dueño"));

        CobroRegistrado cobro = registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(2, "30"), imputar(1, "70")), USUARIO);

        assertThat(cobro.repetida()).isTrue();
        assertThat(cobro.recibo()).isSameAs(existente);
        assertThat(cobro.imputaciones()).hasSize(2);
        assertThat(cobro.cobradoPor()).isEqualTo("Pedro Dueño");
        verify(numeradorRepository, never()).siguienteScoped();
        verify(reciboRepository, never()).save(any());
        verify(cuentaCorriente, never()).asentarCobro(any());
    }

    @Test
    @DisplayName("misma clave con OTRO contenido (total, medio, cliente o imputaciones): CLAVE_CON_OTRO_CONTENIDO (422), sin escribir")
    void repetidaOtroContenido() {
        caminoFeliz(List.of());
        Recibo existente = reciboGuardado(7, CLIENTE, MedioPago.EFECTIVO, "100.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(existente));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(1, "100.00")));

        for (CobranzaPedido distinto : List.of(
                pedido(MedioPago.EFECTIVO, "101", imputar(1, "100")),
                pedido(MedioPago.TRANSFERENCIA, "100", imputar(1, "100")),
                pedido(MedioPago.EFECTIVO, "100", imputar(1, "99")),
                pedido(MedioPago.EFECTIVO, "100", imputar(2, "100")),
                pedido(MedioPago.EFECTIVO, "100"),
                new CobranzaPedido(CLAVE, 99L, MedioPago.EFECTIVO, bd("100"), List.of(imputar(1, "100"))))) {
            assertThatThrownBy(() -> registro.registrar(distinto, USUARIO)).isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                assertThat(e.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
                assertThat(e.motivo().estadoHttp()).isEqualTo(422);
                assertThat(e.getMessage()).isEqualTo("Este cobro ya se registró con otros datos");
            });
        }

        verify(numeradorRepository, never()).siguienteScoped();
        verify(reciboRepository, never()).save(any());
        verify(cuentaCorriente, never()).asentarCobro(any());
    }

    @Test
    @DisplayName("repetida pública (para la fachada): sin recibo es vacío, y no toca caja, cliente, numerador ni asiento")
    void repetidaPublicaSinRecibo() {
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThat(registro.repetida(pedido(MedioPago.EFECTIVO, "10"))).isEmpty();

        verifyNoInteractions(cajaService, clienteDelDocumento, numeradorRepository, cuentaCorriente);
    }

    @Test
    @DisplayName("repetida pública con recibo: lo devuelve aunque la caja esté cerrada (no consulta la caja)")
    void repetidaPublicaConRecibo() {
        Recibo existente = reciboGuardado(7, CLIENTE, MedioPago.EFECTIVO, "10.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(existente));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of());
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Dueño"));

        Optional<CobroRegistrado> cobro = registro.repetida(pedido(MedioPago.EFECTIVO, "10"));

        assertThat(cobro).isPresent();
        assertThat(cobro.get().repetida()).isTrue();
        verifyNoInteractions(cajaService, clienteDelDocumento, numeradorRepository);
    }

    @Test
    @DisplayName("repetida pública con el pedido mal formado es 400 (no revienta comparando)")
    void repetidaPublicaMalFormada() {
        assertThatThrownBy(() -> registro.repetida(new CobranzaPedido(CLAVE, null, MedioPago.EFECTIVO, bd("10"), List.of())))
                .isInstanceOf(DatosInvalidosException.class);
        verifyNoInteractions(reciboRepository);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GET del recibo (CCT3-403)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("recibo(id): arma lo mismo que la repetida (recibo, imputaciones, cobrador) sin tocar caja, cliente ni numerador")
    void reciboPorId() {
        Recibo existente = reciboGuardado(7, CLIENTE, MedioPago.TRANSFERENCIA, "300.00");
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(existente));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(8L, "300.00")));
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Dueño"));

        CobroRegistrado cobro = registro.recibo(RECIBO_ID);

        assertThat(cobro.recibo()).isSameAs(existente);
        assertThat(cobro.imputaciones()).containsExactly(asentada(8L, "300.00"));
        assertThat(cobro.cobradoPor()).isEqualTo("Pedro Dueño");
        verifyNoInteractions(cajaService, clienteDelDocumento, numeradorRepository);
    }

    @Test
    @DisplayName("recibo(id) de otra ferretería o inexistente es 404 y no consulta imputaciones")
    void reciboAjeno() {
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registro.recibo(RECIBO_ID)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recibo no encontrado");

        verifyNoInteractions(cuentaCorriente, cajaService, clienteDelDocumento, numeradorRepository);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Transacciones
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("registrar es una transacción de escritura; repetida es de solo lectura (en tx propia cuando la llama la fachada)")
    void transacciones() throws NoSuchMethodException {
        Method registrar = RegistroCobranza.class.getMethod("registrar", CobranzaPedido.class, Long.class);
        Method repetida = RegistroCobranza.class.getMethod("repetida", CobranzaPedido.class);

        assertThat(registrar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(registrar.getAnnotation(Transactional.class).readOnly()).isFalse();
        assertThat(repetida.getAnnotation(Transactional.class)).isNotNull();
        assertThat(repetida.getAnnotation(Transactional.class).readOnly()).isTrue();
        Method recibo = RegistroCobranza.class.getMethod("recibo", Long.class);
        assertThat(recibo.getAnnotation(Transactional.class)).isNotNull();
        assertThat(recibo.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test
    @DisplayName("sin tenant no se hace nada")
    void sinTenant() {
        TenantContext.clear();

        assertThatThrownBy(() -> registro.registrar(pedido(MedioPago.EFECTIVO, "10"), USUARIO))
                .isInstanceOf(com.ferreteria.tenant.MissingTenantException.class);
        nadaTocado();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CCT3B1-311 (CC-070, CC-038 modificado, CC-067): la repetida de un recibo ANULADO
    // ---------------------------------------------------------------------------------------------------------------

    private static Recibo reciboAnulado(long numero, String total) {
        Recibo recibo = reciboGuardado(numero, CLIENTE, MedioPago.EFECTIVO, total);
        recibo.anular(11L, LocalDateTime.of(2026, 10, 20, 9, 5), 78L, "Cobro mal cargado");
        return recibo;
    }

    @Test
    @DisplayName("CC-070: misma clave y mismo contenido sobre un recibo ANULADO → 200 (repetida=true) con el recibo ANULADO y quién lo anuló; sin numerar, ni recibo nuevo, ni asiento")
    void repetidaDeReciboAnulado() {
        caminoFeliz(List.of());
        Recibo anulado = reciboAnulado(7, "100.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(anulado));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(1, "70.00"), asentada(2, "30.00")));
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Cobrador", 11L, "Ana Dueña"));

        CobroRegistrado cobro = registro.registrar(pedido(MedioPago.EFECTIVO, "100", imputar(2, "30"), imputar(1, "70")), USUARIO);

        assertThat(cobro.repetida()).isTrue();
        assertThat(cobro.recibo()).isSameAs(anulado);
        assertThat(cobro.recibo().isAnulado()).isTrue();
        assertThat(cobro.cobradoPor()).isEqualTo("Pedro Cobrador");
        assertThat(cobro.anuladoPor()).isEqualTo("Ana Dueña");
        assertThat(cobro.imputaciones()).hasSize(2);
        verify(numeradorRepository, never()).siguienteScoped();
        verify(reciboRepository, never()).save(any());
        verify(cuentaCorriente, never()).asentarCobro(any());
    }

    @Test
    @DisplayName("CC-070: la repetida pública (la fachada, sin consultar la caja) devuelve el recibo ANULADO aunque la caja esté cerrada")
    void repetidaPublicaDeReciboAnulado() {
        Recibo anulado = reciboAnulado(7, "10.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(anulado));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of());
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Cobrador", 11L, "Ana Dueña"));

        Optional<CobroRegistrado> cobro = registro.repetida(pedido(MedioPago.EFECTIVO, "10"));

        assertThat(cobro).isPresent();
        assertThat(cobro.get().repetida()).isTrue();
        assertThat(cobro.get().recibo().isAnulado()).isTrue();
        assertThat(cobro.get().anuladoPor()).isEqualTo("Ana Dueña");
        verifyNoInteractions(cajaService, clienteDelDocumento, numeradorRepository);
    }

    @Test
    @DisplayName("CC-070: misma clave con OTRO contenido sobre un recibo anulado sigue siendo CLAVE_CON_OTRO_CONTENIDO (422)")
    void repetidaDeAnuladoConOtroContenido() {
        Recibo anulado = reciboAnulado(7, "100.00");
        when(reciboRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(anulado));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(1, "100.00")));

        assertThatThrownBy(() -> registro.repetida(pedido(MedioPago.EFECTIVO, "250", imputar(1, "100"))))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO));
    }

    @Test
    @DisplayName("el GET del recibo ANULADO trae quién lo anuló; el de uno EMITIDO no (anuladoPor null)")
    void reciboPorIdAnulado() {
        Recibo anulado = reciboAnulado(7, "300.00");
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(anulado));
        when(cuentaCorriente.imputacionesDeRecibo(RECIBO_ID)).thenReturn(List.of(asentada(8L, "300.00")));
        when(cajaRepository.nombresScoped(any())).thenReturn(Map.of(2L, "Pedro Cobrador", 11L, "Ana Dueña"));

        CobroRegistrado cobro = registro.recibo(RECIBO_ID);

        assertThat(cobro.recibo().isAnulado()).isTrue();
        assertThat(cobro.anuladoPor()).isEqualTo("Ana Dueña");
        assertThat(cobro.imputaciones()).hasSize(1);

        Recibo emitido = reciboGuardado(8, CLIENTE, MedioPago.EFECTIVO, "50.00");
        when(reciboRepository.findScoped(RECIBO_ID)).thenReturn(Optional.of(emitido));
        assertThat(registro.recibo(RECIBO_ID).anuladoPor()).isNull();
    }
}
