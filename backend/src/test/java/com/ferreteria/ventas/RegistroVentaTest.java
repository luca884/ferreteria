package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Decision;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Pago;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.ItemRemitoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.ClienteDelDocumento;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConsumidorFinal;
import com.ferreteria.ventas.service.TopeDeDescuento;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.RegistroVenta;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

/** Validaciones de la venta antes de escribir (T-12) y escritura, repetida y sinStock (T-13). */
@ExtendWith(MockitoExtension.class)
class RegistroVentaTest {

    static final Long FERRETERIA = 3L;
    static final Long USUARIO = 9L;
    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final String MENSAJE_404 = "Hay un artículo del ticket que ya no está disponible";

    @Mock
    ArticulosParaVenta articulosParaVenta;

    @Mock
    StockService stockService;

    @Mock
    ComprobanteRepository comprobanteRepository;

    @Mock
    ItemComprobanteRepository itemRepository;

    @Mock
    PagoRepository pagoRepository;

    @Mock
    NumeradorComprobanteRepository numeradorRepository;

    @Mock
    CajaService cajaService;

    @Mock
    FormaPagoRepository formaPagoRepository;

    @Mock
    PresupuestoRepository presupuestoRepository;

    @Mock
    RemitoRepository remitoRepository;

    @Mock
    ItemRemitoRepository itemRemitoRepository;

    @Mock
    com.ferreteria.clientes.api.ClientesParaVenta clientesParaVenta;

    @Mock
    CuentaCorrienteParaVenta cuentaCorriente;

    @Mock
    FacturacionParaVenta facturacion;

    RegistroVenta registro;

    static final Long CAJA = 77L;

    /** El reloj de la venta: el miércoles 2026-10-28 a las 10:15:30 de Buenos Aires. */
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-28T13:15:30Z"), HoraArgentina.ZONA);
    static final LocalDateTime FECHA = LocalDateTime.of(2026, 10, 28, 10, 15, 30);

    /** Las formas de la ferreteria de prueba, por id: una por medio, activas y sin recargo (cada test las cambia). */
    final Map<Long, FormaPago> formasEnLaBase = new java.util.HashMap<>();

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        registro = new RegistroVenta(
                articulosParaVenta, stockService, comprobanteRepository, itemRepository, pagoRepository,
                numeradorRepository, cajaService, formaPagoRepository, presupuestoRepository, remitoRepository,
                itemRemitoRepository, new ClienteDelDocumento(clientesParaVenta), cuentaCorriente,
                new TopeDeDescuento(usuarioId -> java.util.Optional.empty()),
                org.mockito.Mockito.mock(com.ferreteria.ventas.repository.ItemPresupuestoRepository.class),
                new ConsumidorFinal(() -> new java.math.BigDecimal("10000000.00")), facturacion, RELOJ);
        // CU-10: por defecto hay caja abierta hoy. Lenient: los rechazos previos a la caja no la consultan.
        lenient().when(cajaService.cajaParaVender()).thenReturn(CAJA);
        // D5: por defecto la ferreteria no factura (la venta de siempre). Lenient: los rechazos previos no preguntan.
        lenient().when(facturacion.decidir(any())).thenReturn(Decision.SIN_FACTURA);
        for (MedioPago medio : MedioPago.values()) {
            formasEnLaBase.put(formaId(medio), forma(medio, "0.00", true));
        }
        lenient().when(formaPagoRepository.porIdsScoped(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(formasEnLaBase::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    static ArticuloVendible vendible(Long id, String descripcion, String unidad, String precio, boolean activo) {
        return new ArticuloVendible(id, descripcion, unidad, !"UNIDAD".equals(unidad), bd(precio), "IVA_21",
                bd("21.00"), activo, bd("100"));
    }

    static ArticuloVendible tornillo(Long id, String precio) {
        return vendible(id, "Tornillo 1/4", "UNIDAD", precio, true);
    }

    static Renglon renglon(Long articuloId, String cantidad, String precio) {
        return new Renglon(articuloId, bd(cantidad), bd(precio));
    }

    /** El id de la forma de siempre de cada medio: Efectivo 1, Debito 2, Credito 3, Transferencia 4. */
    static Long formaId(MedioPago medio) {
        return medio == null ? null : (long) medio.ordinal() + 1;
    }

    static FormaPago forma(MedioPago medio, String recargo, boolean activa) {
        FormaPago forma = FormaPago.de(FERRETERIA, nombreDe(medio), medio, bd(recargo), activa, medio.ordinal() + 1);
        ReflectionTestUtils.setField(forma, "id", formaId(medio));
        return forma;
    }

    static String nombreDe(MedioPago medio) {
        return switch (medio) {
            case EFECTIVO -> "Efectivo";
            case DEBITO -> "Débito";
            case CREDITO -> "Crédito";
            case TRANSFERENCIA -> "Transferencia";
            case CUENTA_CORRIENTE -> "Cuenta corriente";
        };
    }

    /** Un pago ya guardado con la forma de siempre de ese medio, sin recargo. */
    Pago pagoGuardado(MedioPago medio, String monto) {
        return Pago.de(FERRETERIA, COMPROBANTE, formasEnLaBase.get(formaId(medio)), bd(monto), BigDecimal.ZERO);
    }

    static ConfirmarVenta pedido(String total, MedioPago medio, Renglon... renglones) {
        return new ConfirmarVenta(CLAVE, List.of(renglones), bd(total), formaId(medio));
    }

    void catalogoDevuelve(ArticuloVendible... articulos) {
        when(articulosParaVenta.buscar(anyCollection())).thenReturn(List.of(articulos));
    }

    /** Ningun rechazo escribe nada: ni numero, ni stock, ni comprobante, items o pago. */
    void assertSinEscrituras() {
        verifyNoInteractions(numeradorRepository, stockService, itemRepository, pagoRepository);
        org.mockito.Mockito.verify(comprobanteRepository, org.mockito.Mockito.never()).save(any());
    }

    /** Registra como un Vendedor (el que no puede autorizar excesos): lo que hacían todos los tests antes de la cuenta corriente. */
    VentaRegistrada comoVendedor(ConfirmarVenta pedido, Long usuarioId) {
        return registro.registrar(pedido, usuarioId, false);
    }

    VentaRechazadaException rechazo(ConfirmarVenta pedido) {
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> comoVendedor(pedido, USUARIO));
        assertThat(error).isInstanceOf(VentaRechazadaException.class);
        return (VentaRechazadaException) error;
    }

    // ---------------------------------------------------------------- CU-10: caja

    @Test
    @DisplayName("sin caja abierta la venta se rechaza (CAJA_CERRADA) antes de consultar el catalogo y sin escribir")
    void sinCajaAbierta() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        when(cajaService.cajaParaVender()).thenThrow(
                new VentaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para vender.", List.of()));

        VentaRechazadaException e = rechazo(pedido("10.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        verifyNoInteractions(articulosParaVenta);
        assertSinEscrituras();
    }

    @Test
    @DisplayName("la venta queda en la caja abierta")
    void ventaEnLaCaja() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        assertThat(venta.comprobante().getCajaId()).isEqualTo(CAJA);
    }

    // ---------------------------------------------------------------- T-12: validaciones

    @Test
    @DisplayName("sin ferreteria en el contexto (super admin) falla antes de tocar nada")
    void sinFerreteria() {
        TenantContext.clear();

        assertThatThrownBy(() -> comoVendedor(pedido("10.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00")),
                USUARIO)).isInstanceOf(MissingTenantException.class);
        verifyNoInteractions(articulosParaVenta);
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un articuloId repetido da 400 y no consulta el catalogo")
    void articuloRepetido() {
        ConfirmarVenta pedido = pedido("20.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"),
                renglon(2L, "1", "10.00"), renglon(1L, "1", "10.00"));

        assertThatThrownBy(() -> comoVendedor(pedido, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsKey("renglones[2].articuloId"));
        verifyNoInteractions(articulosParaVenta);
        assertSinEscrituras();
    }

    @Test
    @DisplayName("consulta el catalogo una sola vez, con los ids distintos del ticket")
    void consultaConIdsDelTicket() {
        catalogoDevuelve(tornillo(1L, "10.00"), tornillo(2L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        // las validaciones pasan y falla recien el medio de pago; nos interesan los ids consultados
        assertThatThrownBy(() -> comoVendedor(
                pedido("20.00", null, renglon(1L, "1", "10.00"), renglon(2L, "1", "10.00")), USUARIO))
                .isInstanceOf(DatosInvalidosException.class);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(articulosParaVenta).buscar(ids.capture());
        assertThat(new ArrayList<>(ids.getValue())).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("un articulo inexistente da 404 con el mensaje generico y solo los ids que mando el cliente")
    void articuloInexistente() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(
                pedido("20.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"), renglon(99L, "1", "10.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.ARTICULO_INEXISTENTE);
        assertThat(e.motivo().estadoHttp()).isEqualTo(404);
        assertThat(e.getMessage()).isEqualTo(MENSAJE_404);
        assertThat(e.articulos()).hasSize(1);
        assertThat(e.articulos().get(0).articuloId()).isEqualTo(99L);
        assertThat(e.articulos().get(0).descripcion()).isNull();
        assertSinEscrituras();
    }

    @Test
    @DisplayName("inexistente y ajeno son indistinguibles: el catalogo no devuelve ninguno de los dos")
    void ajenoIgualQueInexistente() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        catalogoDevuelve();
        VentaRechazadaException primero = rechazo(pedido("10.00", MedioPago.EFECTIVO, renglon(999999L, "1", "10.00")));
        VentaRechazadaException segundo = rechazo(pedido("10.00", MedioPago.EFECTIVO, renglon(999999L, "1", "10.00")));

        assertThat(primero.getMessage()).isEqualTo(segundo.getMessage());
        assertThat(primero.motivo()).isEqualTo(segundo.motivo());
        assertThat(primero.articulos()).isEqualTo(segundo.articulos());
    }

    @Test
    @DisplayName("un articulo dado de baja da 422 y lo nombra")
    void articuloDadoDeBaja() {
        catalogoDevuelve(tornillo(1L, "10.00"), vendible(5L, "Martillo de goma", "UNIDAD", "10.00", false));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(
                pedido("20.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"), renglon(5L, "1", "10.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.ARTICULO_DADO_DE_BAJA);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo("\"Martillo de goma\" fue dado de baja. Sacalo del ticket para seguir.");
        assertThat(e.articulos()).hasSize(1);
        assertThat(e.articulos().get(0).articuloId()).isEqualTo(5L);
        assertThat(e.articulos().get(0).descripcion()).isEqualTo("Martillo de goma");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un articulo con precio $0 da 422, lo nombra y no escribe nada")
    void articuloSinPrecio() {
        catalogoDevuelve(tornillo(1L, "10.00"), vendible(6L, "Arandela suelta", "UNIDAD", "0.00", true));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(
                pedido("10.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"), renglon(6L, "1", "0.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.ARTICULO_SIN_PRECIO);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.getMessage()).isEqualTo(
                "\"Arandela suelta\" no tiene precio. Sacalo del ticket y pedile al dueño que le cargue el costo.");
        assertThat(e.articulos()).extracting(a -> a.articuloId()).containsExactly(6L);
        assertThat(e.articulos().get(0).descripcion()).isEqualTo("Arandela suelta");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("dos articulos dados de baja: el mensaje nombra a los dos")
    void dosDadosDeBaja() {
        catalogoDevuelve(vendible(1L, "Martillo", "UNIDAD", "10.00", false),
                vendible(2L, "Pinza", "UNIDAD", "10.00", false), tornillo(3L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(pedido("30.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"),
                renglon(2L, "1", "10.00"), renglon(3L, "1", "10.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.ARTICULO_DADO_DE_BAJA);
        assertThat(e.getMessage()).contains("\"Martillo\"").contains("\"Pinza\"").doesNotContain("Tornillo");
        assertThat(e.articulos()).extracting(a -> a.articuloId()).containsExactly(1L, 2L);
        assertSinEscrituras();
    }

    @Test
    @DisplayName("cantidad no entera de un articulo por UNIDAD da 400 y nombra el articulo")
    void cantidadNoEnteraEnUnidad() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comoVendedor(pedido("25.00", MedioPago.EFECTIVO, renglon(1L, "2.5", "10.00")),
                USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("renglones[0].cantidad",
                                "\"Tornillo 1/4\" se vende por unidad: la cantidad tiene que ser un número entero."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("cantidad 0 o con mas de 3 decimales da 400; cada renglon con su indice")
    void cantidadesInvalidas() {
        catalogoDevuelve(tornillo(1L, "10.00"), vendible(2L, "Cable", "METRO", "10.00", true));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comoVendedor(pedido("0.00", MedioPago.EFECTIVO, renglon(1L, "0", "10.00"),
                renglon(2L, "0.0005", "10.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("renglones[0].cantidad", "renglones[1].cantidad"));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("precio cambiado da 409 con precio mostrado, precio actual, total del renglon y total actual")
    void precioCambiado() {
        catalogoDevuelve(vendible(1L, "Martillo", "UNIDAD", "1650.00", true));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(pedido("3000.00", MedioPago.EFECTIVO, renglon(1L, "2", "1500.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.PRECIOS_CAMBIADOS);
        assertThat(e.motivo().estadoHttp()).isEqualTo(409);
        assertThat(e.total()).isEqualByComparingTo("3300.00");
        assertThat(e.articulos()).hasSize(1);
        VentaRechazadaException.Articulo a = e.articulos().get(0);
        assertThat(a.articuloId()).isEqualTo(1L);
        assertThat(a.descripcion()).isEqualTo("Martillo");
        assertThat(a.precioMostrado()).isEqualByComparingTo("1500.00");
        assertThat(a.precioActual()).isEqualByComparingTo("1650.00");
        assertThat(a.totalRenglon()).isEqualByComparingTo("3300.00");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("solo lista los renglones que cambiaron de precio")
    void soloListaLosCambiados() {
        catalogoDevuelve(vendible(1L, "Martillo", "UNIDAD", "1650.00", true), tornillo(2L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(pedido("3010.00", MedioPago.EFECTIVO, renglon(1L, "2", "1500.00"),
                renglon(2L, "1", "10.00")));

        assertThat(e.articulos()).extracting(a -> a.articuloId()).containsExactly(1L);
        assertThat(e.total()).isEqualByComparingTo("3310.00");
    }

    @Test
    @DisplayName("mismos precios pero total mostrado distinto del recalculado tambien da 409")
    void totalDistinto() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        VentaRechazadaException e = rechazo(pedido("99.00", MedioPago.EFECTIVO, renglon(1L, "2", "10.00")));

        assertThat(e.motivo()).isEqualTo(Motivo.PRECIOS_CAMBIADOS);
        assertThat(e.articulos()).isEmpty();
        assertThat(e.total()).isEqualByComparingTo("20.00");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("el precio mostrado 10 y el actual 10.00 son el mismo (compara por valor, no por escala)")
    void precioIgualConOtraEscala() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        // llega hasta el medio de pago: si el precio se tomara como cambiado saltaria el 409
        assertThatThrownBy(() -> comoVendedor(pedido("10", null, renglon(1L, "1", "10")), USUARIO))
                .isInstanceOf(DatosInvalidosException.class);
    }

    @Test
    @DisplayName("sin forma de pago ni lista de pagos da 400 en el campo formaPagoId")
    void sinFormaDePago() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comoVendedor(pedido("10.00", null, renglon(1L, "1", "10.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("formaPagoId", "Elegí una forma de pago."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("precedencia: 404 gana a baja, baja a cantidad, cantidad a precios y precios a pago")
    void precedencia() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        // 404 + baja + cantidad + precio + pago: gana el 404
        when(articulosParaVenta.buscar(anyCollection())).thenReturn(
                List.of(vendible(1L, "Martillo", "UNIDAD", "50.00", false), tornillo(2L, "10.00")));
        assertThat(rechazo(pedido("1.00", null, renglon(1L, "1", "10.00"), renglon(2L, "2.5", "10.00"),
                renglon(99L, "1", "10.00"))).motivo()).isEqualTo(Motivo.ARTICULO_INEXISTENTE);

        // baja + cantidad + precio + pago: gana la baja
        assertThat(rechazo(pedido("1.00", null, renglon(1L, "1", "10.00"), renglon(2L, "2.5", "10.00"))).motivo())
                .isEqualTo(Motivo.ARTICULO_DADO_DE_BAJA);

        // cantidad + precio + pago: gana la cantidad (400)
        catalogoDevuelve(tornillo(2L, "10.00"));
        assertThatThrownBy(() -> comoVendedor(pedido("1.00", null, renglon(2L, "2.5", "99.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("renglones[0].cantidad"));

        // precio + pago: gana el 409
        assertThat(rechazo(pedido("99.00", null, renglon(2L, "1", "99.00"))).motivo())
                .isEqualTo(Motivo.PRECIOS_CAMBIADOS);
        assertSinEscrituras();
    }

    // ---------------------------------------------------------------- T-13: escritura, repetida y sinStock

    static final Long COMPROBANTE = 31L;
    static final long NUMERO = 12L;

    /** Deja listas las escrituras: el numerador da 12, la cabecera recibe el id 31 y el stock devuelve lo pedido. */
    void escrituraLista(Map<Long, BigDecimal> stockResultante) {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        when(numeradorRepository.siguienteScoped()).thenReturn(NUMERO);
        when(comprobanteRepository.save(any(Comprobante.class))).thenAnswer(inv -> {
            Comprobante c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", COMPROBANTE);
            return c;
        });
        when(stockService.registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), any())).thenReturn(stockResultante);
        when(itemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        // El pago guardado recibe el id 100, 101... (IDENTITY): el asiento de cuenta corriente lo referencia.
        java.util.concurrent.atomic.AtomicLong idsDePago = new java.util.concurrent.atomic.AtomicLong(100);
        when(pagoRepository.save(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", idsDePago.getAndIncrement());
            return p;
        });
    }

    /** Las formas como las ve el servicio: la de ese medio, con su recargo. */
    void conRecargo(MedioPago medio, String recargo) {
        formasEnLaBase.put(formaId(medio), forma(medio, recargo, true));
    }

    /** C1 (3 u a 1.210,00) y C3 (2,5 m a 333,33): total 4.463,33. */
    ConfirmarVenta ventaC1C3(MedioPago medio) {
        return pedido("4463.33", medio, renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33"));
    }

    void catalogoC1C3() {
        catalogoDevuelve(vendible(1L, "Tornillo 1/4", "UNIDAD", "1210.00", true),
                vendible(2L, "Cable 2,5 mm", "METRO", "333.33", true));
    }

    @SuppressWarnings("unchecked")
    List<ItemComprobante> itemsGuardados() {
        ArgumentCaptor<List<ItemComprobante>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("escribe en orden: numerador, cabecera, stock, items y pago")
    void ordenDeEscritura() {
        catalogoC1C3();
        escrituraLista(Map.of(1L, bd("7"), 2L, bd("7.5")));

        comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        InOrder orden = inOrder(numeradorRepository, comprobanteRepository, stockService, itemRepository,
                pagoRepository);
        orden.verify(numeradorRepository).siguienteScoped();
        orden.verify(comprobanteRepository).save(any(Comprobante.class));
        orden.verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), any());
        orden.verify(itemRepository).saveAll(any());
        orden.verify(pagoRepository).save(any(Pago.class));
    }

    @Test
    @DisplayName("la cabecera es VENTA SIN_FACTURAR con numero, clave, usuario y totales de los renglones")
    void cabecera() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3(MedioPago.DEBITO), USUARIO);

        Comprobante c = venta.comprobante();
        assertThat(c.getTipo()).isEqualTo(TipoComprobante.VENTA);
        assertThat(c.getEstado()).isEqualTo(EstadoComprobante.SIN_FACTURAR);
        assertThat(c.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(c.getNumero()).isEqualTo(NUMERO);
        assertThat(c.getClaveIdempotencia()).isEqualTo(CLAVE);
        assertThat(c.getUsuarioId()).isEqualTo(USUARIO);
        assertThat(c.getClienteId()).isNull();
        assertThat(c.getTotal()).isEqualByComparingTo("4463.33");
        assertThat(c.getNeto()).isEqualByComparingTo("3688.70");
        assertThat(c.getIva()).isEqualByComparingTo("774.63");
        assertThat(venta.repetida()).isFalse();
    }

    // ---- cliente (CL-004)

    static ConfirmarVenta conCliente(ConfirmarVenta p, Long clienteId) {
        return new ConfirmarVenta(p.clave(), p.renglones(), p.totalMostrado(), p.formaPagoId(),
                p.descuentoPorcentaje(), p.pagos(), p.presupuestoId(), p.remitoId(), p.facturable(), clienteId);
    }

    @Test
    @DisplayName("la venta con cliente guarda su id y una copia de su razón social; sin cliente guarda nulo")
    void ventaConCliente() {
        catalogoC1C3();
        escrituraLista(Map.of(1L, bd("7"), 2L, bd("7.5")));
        when(clientesParaVenta.tomarParaVenta(5L)).thenReturn(Optional.of(
                new com.ferreteria.clientes.api.ClienteReferencia(5L, "Ferretería Norte SRL", true,
                        com.ferreteria.clientes.api.ClienteReferencia.CondicionIva.CONSUMIDOR_FINAL, null, null, null)));

        Comprobante conCliente = comoVendedor(conCliente(ventaC1C3(MedioPago.EFECTIVO), 5L), USUARIO)
                .comprobante();

        assertThat(conCliente.getClienteId()).isEqualTo(5L);
        assertThat(conCliente.getClienteNombre()).isEqualTo("Ferretería Norte SRL");
        assertThat(ventaC1C3(MedioPago.EFECTIVO).clienteId()).isNull();
    }

    @Test
    @DisplayName("con un cliente inexistente o desactivado rechaza antes de escribir: ni número, ni stock, ni comprobante")
    void ventaConClienteInvalidoNoEscribe() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        when(clientesParaVenta.tomarParaVenta(9L)).thenReturn(Optional.empty());
        when(clientesParaVenta.tomarParaVenta(8L)).thenReturn(Optional.of(
                new com.ferreteria.clientes.api.ClienteReferencia(8L, "Viejo", false,
                        com.ferreteria.clientes.api.ClienteReferencia.CondicionIva.CONSUMIDOR_FINAL, null, null, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> comoVendedor(conCliente(ventaC1C3(MedioPago.EFECTIVO), 9L), USUARIO))
                .isInstanceOf(com.ferreteria.common.exception.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> comoVendedor(conCliente(ventaC1C3(MedioPago.EFECTIVO), 8L), USUARIO))
                .isInstanceOf(com.ferreteria.common.exception.ReglaDeNegocioException.class);

        assertSinEscrituras();
    }

    @Test
    @DisplayName("repetir con otro cliente es CLAVE_CON_OTRO_CONTENIDO; con el mismo es la misma venta")
    void ventaRepetidaConCliente() {
        Comprobante guardado = ventaGuardadaC1C3();
        ReflectionTestUtils.setField(guardado, "clienteId", 5L);
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(guardado));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(itemsGuardadosC1C3());
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.EFECTIVO, "4463.33")));

        assertThat(comoVendedor(conCliente(ventaC1C3(MedioPago.EFECTIVO), 5L), USUARIO).repetida()).isTrue();
        assertThat(rechazo(conCliente(ventaC1C3(MedioPago.EFECTIVO), 6L)).motivo())
                .isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
        assertThat(rechazo(ventaC1C3(MedioPago.EFECTIVO)).motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
    }

    // ---- venta no facturable

    static ConfirmarVenta noFacturable(ConfirmarVenta p) {
        return new ConfirmarVenta(p.clave(), p.renglones(), p.totalMostrado(), p.formaPagoId(),
                p.descuentoPorcentaje(), p.pagos(), p.presupuestoId(), p.remitoId(), false, p.clienteId(), p.autorizaExceso());
    }

    @Test
    @DisplayName("un pedido sin la marca es facturable: la venta de siempre queda SIN_FACTURAR")
    void pedidoSinMarcaEsFacturable() {
        assertThat(ventaC1C3(MedioPago.EFECTIVO).facturable()).isTrue();
        assertThat(noFacturable(ventaC1C3(MedioPago.EFECTIVO)).facturable()).isFalse();
    }

    @Test
    @DisplayName("la venta no facturable queda VENTA NO_FACTURABLE, con el mismo numerador, y mueve caja, stock e items igual")
    void ventaNoFacturable() {
        catalogoC1C3();
        escrituraLista(Map.of(1L, bd("7"), 2L, bd("7.5")));

        VentaRegistrada venta = comoVendedor(noFacturable(ventaC1C3(MedioPago.EFECTIVO)), USUARIO);

        Comprobante c = venta.comprobante();
        assertThat(c.getTipo()).isEqualTo(TipoComprobante.VENTA);
        assertThat(c.getEstado()).isEqualTo(EstadoComprobante.NO_FACTURABLE);
        assertThat(c.getNumero()).isEqualTo(NUMERO);
        assertThat(c.getCajaId()).isEqualTo(CAJA);
        assertThat(c.getTotal()).isEqualByComparingTo("4463.33");
        verify(numeradorRepository).siguienteScoped();
        verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), any());
        assertThat(itemsGuardados()).hasSize(2);
        verify(pagoRepository).save(any(Pago.class));
    }

    @Test
    @DisplayName("repetir con la misma marca es la misma venta; con otra marca, CLAVE_CON_OTRO_CONTENIDO")
    void repetidaConLaMarca() {
        Comprobante guardado = ventaGuardadaC1C3();
        ReflectionTestUtils.setField(guardado, "estado", EstadoComprobante.NO_FACTURABLE);
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(guardado));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(itemsGuardadosC1C3());
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.EFECTIVO, "4463.33")));

        assertThat(comoVendedor(noFacturable(ventaC1C3(MedioPago.EFECTIVO)), USUARIO).repetida()).isTrue();
        assertThat(rechazo(ventaC1C3(MedioPago.EFECTIVO)).motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
    }

    @Test
    @DisplayName("repetir una venta facturable pidiendola no facturable es CLAVE_CON_OTRO_CONTENIDO")
    void repetidaFacturablePedidaNoFacturable() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(ventaGuardadaC1C3()));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(itemsGuardadosC1C3());
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.EFECTIVO, "4463.33")));

        assertThat(rechazo(noFacturable(ventaC1C3(MedioPago.EFECTIVO))).motivo())
                .isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
    }

    @Test
    @DisplayName("cada item copia descripcion, unidad, cantidad, precio, alicuota del servidor, total, neto e IVA")
    void itemsCopianLosDatos() {
        catalogoC1C3();
        escrituraLista(Map.of());

        comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        List<ItemComprobante> items = itemsGuardados();
        assertThat(items).hasSize(2);
        ItemComprobante a = items.get(0);
        assertThat(a.getComprobanteId()).isEqualTo(COMPROBANTE);
        assertThat(a.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(a.getRenglon()).isEqualTo(1);
        assertThat(a.getArticuloId()).isEqualTo(1L);
        assertThat(a.getDescripcion()).isEqualTo("Tornillo 1/4");
        assertThat(a.getUnidad()).isEqualTo("UNIDAD");
        assertThat(a.getCantidad()).isEqualByComparingTo("3");
        assertThat(a.getPrecioUnitario()).isEqualByComparingTo("1210.00");
        assertThat(a.getAlicuotaIva()).isEqualTo("IVA_21");
        assertThat(a.getTotal()).isEqualByComparingTo("3630.00");
        assertThat(a.getNeto()).isEqualByComparingTo("3000.00");
        assertThat(a.getIva()).isEqualByComparingTo("630.00");
        ItemComprobante b = items.get(1);
        assertThat(b.getRenglon()).isEqualTo(2);
        assertThat(b.getUnidad()).isEqualTo("METRO");
        assertThat(b.getCantidad()).isEqualByComparingTo("2.5");
        assertThat(b.getTotal()).isEqualByComparingTo("833.33");
        assertThat(b.getNeto()).isEqualByComparingTo("688.70");
        assertThat(b.getIva()).isEqualByComparingTo("144.63");
    }

    @Test
    @DisplayName("un solo pago por el total, con el medio elegido y ligado al comprobante")
    void unSoloPago() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3(MedioPago.TRANSFERENCIA), USUARIO);

        verify(pagoRepository, times(1)).save(any(Pago.class));
        assertThat(venta.pagos()).hasSize(1);
        Pago pago = venta.pagos().get(0);
        assertThat(pago.getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(pago.getMonto()).isEqualByComparingTo("4463.33");
        assertThat(pago.getComprobanteId()).isEqualTo(COMPROBANTE);
        assertThat(pago.getFerreteriaId()).isEqualTo(FERRETERIA);
    }

    // ---- pago mixto (GDS-07)

    static ConfirmarVenta.PagoPedido pagoDe(MedioPago medio, String monto) {
        return new ConfirmarVenta.PagoPedido(formaId(medio), bd(monto));
    }

    /** El ticket C1 + C3 (4.463,33) pagado con la lista de pagos y sin forma unica. */
    ConfirmarVenta ventaC1C3Mixta(ConfirmarVenta.PagoPedido... pagos) {
        return new ConfirmarVenta(CLAVE, List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")),
                bd("4463.33"), null, BigDecimal.ZERO, List.of(pagos));
    }

    @Test
    @DisplayName("un pago mixto guarda un Pago por medio, ligado al comprobante, y la suma es el total")
    void pagoMixto() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3Mixta(
                pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.DEBITO, "3463.33")), USUARIO);

        assertThat(venta.pagos()).hasSize(2);
        assertThat(venta.pagos().get(0).getMedio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(venta.pagos().get(0).getMonto()).isEqualByComparingTo("1000.00");
        assertThat(venta.pagos().get(1).getMedio()).isEqualTo(MedioPago.DEBITO);
        assertThat(venta.pagos().get(1).getMonto()).isEqualByComparingTo("3463.33");
        assertThat(venta.pagos()).allSatisfy(p -> {
            assertThat(p.getComprobanteId()).isEqualTo(COMPROBANTE);
            assertThat(p.getFerreteriaId()).isEqualTo(FERRETERIA);
        });
        verify(pagoRepository, times(2)).save(any(Pago.class));
    }

    @Test
    @DisplayName("un solo pago en la lista es lo mismo que un medio unico")
    void unSoloPagoEnLaLista() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(
                ventaC1C3Mixta(pagoDe(MedioPago.CREDITO, "4463.33")), USUARIO);

        assertThat(venta.pagos()).hasSize(1);
        assertThat(venta.pagos().get(0).getMedio()).isEqualTo(MedioPago.CREDITO);
        assertThat(venta.pagos().get(0).getMonto()).isEqualByComparingTo("4463.33");
    }

    @Test
    @DisplayName("los pagos que no suman el total dan 400 en pagos y no escriben nada")
    void pagosQueNoSumanElTotal() {
        catalogoC1C3();

        assertThatThrownBy(() -> comoVendedor(ventaC1C3Mixta(
                pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.DEBITO, "3000.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos", "Los pagos tienen que sumar el total de la venta."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("una forma repetida en los pagos da 400 en el pago repetido")
    void formaRepetida() {
        catalogoC1C3();

        assertThatThrownBy(() -> comoVendedor(ventaC1C3Mixta(
                pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.EFECTIVO, "3463.33")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos[1].formaPagoId", "Cada forma de pago va una sola vez."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("mandar la forma unica y la lista de pagos juntas da 400")
    void formaUnicaYListaJuntas() {
        catalogoC1C3();

        ConfirmarVenta ambos = new ConfirmarVenta(CLAVE,
                List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")), bd("4463.33"),
                formaId(MedioPago.EFECTIVO), BigDecimal.ZERO, List.of(pagoDe(MedioPago.EFECTIVO, "4463.33")));

        assertThatThrownBy(() -> comoVendedor(ambos, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos", "Mandá una sola forma de pago o la lista de pagos, no las dos."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un pago con monto cero o negativo da 400 en ese pago")
    void montoNoPositivo() {
        catalogoC1C3();

        assertThatThrownBy(() -> comoVendedor(ventaC1C3Mixta(
                pagoDe(MedioPago.EFECTIVO, "0.00"), pagoDe(MedioPago.DEBITO, "4463.33")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos[0].monto", "El monto del pago tiene que ser mayor a cero."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("el pago mixto con descuento suma contra el total ya descontado")
    void pagoMixtoConDescuento() {
        catalogoDe100();
        escrituraLista(Map.of());
        ConfirmarVenta pedido = new ConfirmarVenta(CLAVE,
                List.of(new Renglon(1L, bd("2"), bd("100.00"), bd("10.00"))), bd("162.00"), null, bd("10.00"),
                List.of(pagoDe(MedioPago.EFECTIVO, "62.00"), pagoDe(MedioPago.TRANSFERENCIA, "100.00")));

        VentaRegistrada venta = comoVendedor(pedido, USUARIO);

        assertThat(venta.pagos()).extracting(Pago::getMonto)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("62.00"), bd("100.00"));
    }

    @Test
    @DisplayName("repetir con el mismo reparto de pagos es la misma venta; con otro reparto o medio, CLAVE_CON_OTRO_CONTENIDO")
    void repetidaConPagosMixtos() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(ventaGuardadaC1C3()));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(itemsGuardadosC1C3());
        when(pagoRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(
                pagoGuardado(MedioPago.EFECTIVO, "1000.00"), pagoGuardado(MedioPago.DEBITO, "3463.33")));

        VentaRegistrada igual = comoVendedor(ventaC1C3Mixta(
                pagoDe(MedioPago.DEBITO, "3463.33"), pagoDe(MedioPago.EFECTIVO, "1000.00")), USUARIO);
        assertThat(igual.repetida()).isTrue();
        assertThat(igual.pagos()).hasSize(2);

        for (ConfirmarVenta otro : List.of(
                ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "2000.00"), pagoDe(MedioPago.DEBITO, "2463.33")),
                ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.CREDITO, "3463.33")),
                ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "4463.33")),
                ventaC1C3(MedioPago.EFECTIVO))) {
            assertThat(rechazo(otro).motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
        }
    }

    Comprobante ventaGuardadaC1C3() {
        Comprobante c = Comprobante.venta(FERRETERIA, NUMERO, CLAVE, USUARIO,
                TotalesVenta.de(List.of(RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")),
                        RenglonVenta.de(bd("333.33"), bd("2.5"), bd("21.00")))), CAJA, FECHA);
        ReflectionTestUtils.setField(c, "id", COMPROBANTE);
        return c;
    }

    List<ItemComprobante> itemsGuardadosC1C3() {
        return List.of(
                ItemComprobante.de(FERRETERIA, COMPROBANTE, 1, 1L, "Tornillo 1/4", "UNIDAD", "IVA_21", null,
                        RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00"))),
                ItemComprobante.de(FERRETERIA, COMPROBANTE, 2, 2L, "Cable 2,5 mm", "METRO", "IVA_21", null,
                        RenglonVenta.de(bd("333.33"), bd("2.5"), bd("21.00"))));
    }

    // ---- descuentos (GDS-12)

    /** 2 u a 100,00 con 10 % en el renglon y 10 % global: 200 -> 180 -> 162. */
    ConfirmarVenta ventaConDescuentos(String total) {
        return new ConfirmarVenta(CLAVE, List.of(new Renglon(1L, bd("2"), bd("100.00"), bd("10.00"))), bd(total),
                formaId(MedioPago.EFECTIVO), bd("10.00"));
    }

    void catalogoDe100() {
        catalogoDevuelve(vendible(1L, "Tornillo 1/4", "UNIDAD", "100.00", true));
    }

    @Test
    @DisplayName("el descuento del renglon y el global bajan el total: la cabecera guarda subtotal, descuento global y total")
    void descuentosEnLaCabecera() {
        catalogoDe100();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaConDescuentos("162.00"), USUARIO);

        Comprobante c = venta.comprobante();
        assertThat(c.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(c.getDescuentoPorcentaje()).isEqualByComparingTo("10.00");
        assertThat(c.getTotal()).isEqualByComparingTo("162.00");
        assertThat(c.getNeto().add(c.getIva())).isEqualByComparingTo("162.00");
        assertThat(venta.pagos().get(0).getMonto()).isEqualByComparingTo("162.00");
    }

    @Test
    @DisplayName("el item guarda el bruto, el descuento del renglon y el total ya descontado")
    void descuentosEnElItem() {
        catalogoDe100();
        escrituraLista(Map.of());

        comoVendedor(ventaConDescuentos("162.00"), USUARIO);

        ItemComprobante item = itemsGuardados().get(0);
        assertThat(item.getBruto()).isEqualByComparingTo("200.00");
        assertThat(item.getDescuentoPorcentaje()).isEqualByComparingTo("10.00");
        assertThat(item.getTotal()).isEqualByComparingTo("162.00");
        assertThat(item.getPrecioUnitario()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("sin descuentos en el pedido la venta es la de siempre: porcentajes en cero y subtotal igual al total")
    void sinDescuentosEnElPedido() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        assertThat(venta.comprobante().getSubtotal()).isEqualByComparingTo("4463.33");
        assertThat(venta.comprobante().getDescuentoPorcentaje()).isEqualByComparingTo("0");
        assertThat(itemsGuardados().get(0).getDescuentoPorcentaje()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("si el cajero mostro el total sin el descuento que mando, da 409 con el total actual")
    void totalMostradoSinElDescuento() {
        catalogoDe100();

        VentaRechazadaException error = rechazo(ventaConDescuentos("200.00"));

        assertThat(error.motivo()).isEqualTo(Motivo.PRECIOS_CAMBIADOS);
        assertThat(error.total()).isEqualByComparingTo("162.00");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un descuento fuera de 0 a 99,99 % da 400 con el campo del renglon o el global")
    void descuentoFueraDeRango() {
        catalogoDe100();

        Throwable delRenglon = org.assertj.core.api.Assertions.catchThrowable(() -> comoVendedor(
                new ConfirmarVenta(CLAVE, List.of(new Renglon(1L, bd("1"), bd("100.00"), bd("100.00"))), bd("0.00"),
                        formaId(MedioPago.EFECTIVO), BigDecimal.ZERO), USUARIO));
        Throwable global = org.assertj.core.api.Assertions.catchThrowable(() -> comoVendedor(
                new ConfirmarVenta(CLAVE, List.of(new Renglon(1L, bd("1"), bd("100.00"))), bd("100.00"),
                        formaId(MedioPago.EFECTIVO), bd("-1")), USUARIO));

        assertThat(delRenglon).isInstanceOf(DatosInvalidosException.class);
        assertThat(((DatosInvalidosException) delRenglon).errors()).containsKey("renglones[0].descuentoPorcentaje");
        assertThat(global).isInstanceOf(DatosInvalidosException.class);
        assertThat(((DatosInvalidosException) global).errors()).containsKey("descuentoPorcentaje");
        assertSinEscrituras();
    }

    @Test
    @DisplayName("misma clave y mismo descuento es la misma venta; con otro descuento es CLAVE_CON_OTRO_CONTENIDO")
    void repetidaConDescuentos() {
        Comprobante comprobante = Comprobante.venta(FERRETERIA, NUMERO, CLAVE, USUARIO,
                TotalesVenta.de(List.of(RenglonVenta.de(bd("100.00"), bd("2"), bd("21.00"), bd("10.00"), bd("10.00")))),
                CAJA, bd("10.00"), FECHA);
        ReflectionTestUtils.setField(comprobante, "id", COMPROBANTE);
        ItemComprobante item = ItemComprobante.de(FERRETERIA, COMPROBANTE, 1, 1L, "Tornillo 1/4", "UNIDAD", "IVA_21", null,
                RenglonVenta.de(bd("100.00"), bd("2"), bd("21.00"), bd("10.00"), bd("10.00")));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(comprobante));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(item));
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.EFECTIVO, "162.00")));

        assertThat(comoVendedor(ventaConDescuentos("162.00"), USUARIO).repetida()).isTrue();

        ConfirmarVenta otroGlobal = new ConfirmarVenta(CLAVE,
                List.of(new Renglon(1L, bd("2"), bd("100.00"), bd("10.00"))), bd("162.00"), formaId(MedioPago.EFECTIVO),
                bd("5.00"));
        ConfirmarVenta otroDelRenglon = new ConfirmarVenta(CLAVE,
                List.of(new Renglon(1L, bd("2"), bd("100.00"), bd("5.00"))), bd("162.00"), formaId(MedioPago.EFECTIVO),
                bd("10.00"));
        for (ConfirmarVenta otro : List.of(otroGlobal, otroDelRenglon)) {
            assertThat(rechazo(otro).motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
        }
    }

    @Test
    @DisplayName("le pide al stock una salida por renglon, con la cantidad vendida")
    @SuppressWarnings("unchecked")
    void salidasDeStock() {
        catalogoC1C3();
        escrituraLista(Map.of());

        comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        ArgumentCaptor<List<SalidaStock>> salidas = ArgumentCaptor.forClass(List.class);
        verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), salidas.capture());
        assertThat(salidas.getValue()).hasSize(2);
        assertThat(salidas.getValue().get(0).articuloId()).isEqualTo(1L);
        assertThat(salidas.getValue().get(0).cantidad()).isEqualByComparingTo("3");
        assertThat(salidas.getValue().get(1).articuloId()).isEqualTo(2L);
        assertThat(salidas.getValue().get(1).cantidad()).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("sinStock avisa solo si el stock resultante queda menor que 0")
    void sinStockSoloNegativo() {
        catalogoDevuelve(vendible(1L, "Justo", "UNIDAD", "10.00", true), vendible(2L, "Corto", "UNIDAD", "10.00", true),
                vendible(3L, "Sobra", "UNIDAD", "10.00", true));
        escrituraLista(Map.of(1L, bd("0.000"), 2L, bd("-3.000"), 3L, bd("4.000")));

        VentaRegistrada venta = comoVendedor(pedido("110.00", MedioPago.EFECTIVO, renglon(1L, "5", "10.00"),
                renglon(2L, "5", "10.00"), renglon(3L, "1", "10.00")), USUARIO);

        assertThat(venta.sinStock()).hasSize(1);
        assertThat(venta.sinStock().get(0).articuloId()).isEqualTo(2L);
        assertThat(venta.sinStock().get(0).descripcion()).isEqualTo("Corto");
        assertThat(venta.sinStock().get(0).stockActual()).isEqualByComparingTo("-3");
    }

    // ---- clave repetida

    Comprobante ventaGuardada() {
        Comprobante c = Comprobante.venta(FERRETERIA, NUMERO, CLAVE, USUARIO,
                TotalesVenta.de(List.of(RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")))), CAJA, FECHA);
        ReflectionTestUtils.setField(c, "id", COMPROBANTE);
        return c;
    }

    ItemComprobante itemGuardado() {
        return ItemComprobante.de(FERRETERIA, COMPROBANTE, 1, 1L, "Tornillo 1/4", "UNIDAD", "IVA_21", null,
                RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")));
    }

    void ventaYaRegistrada() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(ventaGuardada()));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(itemGuardado()));
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.EFECTIVO, "3630.00")));
    }

    void assertNadaNuevo() {
        verifyNoInteractions(numeradorRepository, stockService, articulosParaVenta);
        verify(comprobanteRepository, never()).save(any());
        verify(itemRepository, never()).saveAll(any());
        verify(pagoRepository, never()).save(any());
    }

    @Test
    @DisplayName("misma clave y mismo contenido devuelve la venta original marcada repetida, sin numerador ni stock")
    void repetidaMismoContenido() {
        ventaYaRegistrada();

        VentaRegistrada venta = comoVendedor(
                pedido("3630.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00")), USUARIO);

        assertThat(venta.repetida()).isTrue();
        assertThat(venta.comprobante().getId()).isEqualTo(COMPROBANTE);
        assertThat(venta.comprobante().getNumero()).isEqualTo(NUMERO);
        assertThat(venta.items()).hasSize(1);
        assertThat(venta.pagos().get(0).getMedio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(venta.sinStock()).isEmpty();
        assertNadaNuevo();
    }

    @Test
    @DisplayName("misma clave y mismo contenido con otra escala de los numeros sigue siendo la misma venta")
    void repetidaOtraEscala() {
        ventaYaRegistrada();

        VentaRegistrada venta = comoVendedor(
                pedido("3630", MedioPago.EFECTIVO, renglon(1L, "3.000", "1210")), USUARIO);

        assertThat(venta.repetida()).isTrue();
    }

    @Test
    @DisplayName("misma clave con otro contenido da CLAVE_CON_OTRO_CONTENIDO (422) y no escribe nada")
    void repetidaOtroContenido() {
        List<ConfirmarVenta> variantes = List.of(
                pedido("3630.00", MedioPago.DEBITO, renglon(1L, "3", "1210.00")),
                pedido("3600.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00")),
                pedido("2420.00", MedioPago.EFECTIVO, renglon(1L, "2", "1210.00")),
                pedido("3630.00", MedioPago.EFECTIVO, renglon(1L, "3", "1200.00")),
                pedido("3630.00", MedioPago.EFECTIVO, renglon(2L, "3", "1210.00")),
                pedido("3640.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00"), renglon(2L, "1", "10.00")));
        ventaYaRegistrada();

        for (ConfirmarVenta variante : variantes) {
            VentaRechazadaException e = rechazo(variante);
            assertThat(e.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
            assertThat(e.motivo().estadoHttp()).isEqualTo(422);
            assertThat(e.getMessage()).isEqualTo("Esta venta ya se registró con otros datos");
        }
        assertNadaNuevo();
    }

    @Test
    @DisplayName("repetida(pedido): vacio si la clave no existe, la venta si existe con el mismo contenido")
    void repetidaPorClave() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        assertThat(registro.repetida(pedido("3630.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00")))).isEmpty();

        ventaYaRegistrada();
        Optional<VentaRegistrada> venta =
                registro.repetida(pedido("3630.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00")));
        assertThat(venta).isPresent();
        assertThat(venta.get().repetida()).isTrue();
        assertThat(venta.get().comprobante().getNumero()).isEqualTo(NUMERO);
    }

    @Test
    @DisplayName("repetida(pedido) con otro contenido tambien lanza CLAVE_CON_OTRO_CONTENIDO")
    void repetidaPorClaveOtroContenido() {
        ventaYaRegistrada();

        assertThatThrownBy(() -> registro.repetida(pedido("3630.00", MedioPago.CREDITO, renglon(1L, "3", "1210.00"))))
                .isInstanceOfSatisfying(VentaRechazadaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO));
    }

    // ---- recargo de la forma de pago (GDS-11)

    /** El C1 + C3 (4.463,33) pagado entero con credito al 10 %: recargo 446,33 y total 4.909,66. */
    ConfirmarVenta ventaC1C3ConCredito10(String totalMostrado) {
        return new ConfirmarVenta(CLAVE, List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")),
                bd(totalMostrado), formaId(MedioPago.CREDITO));
    }

    @Test
    @DisplayName("una forma con recargo suma el recargo al total, lo reparte entre los renglones y lo guarda en el pago")
    void formaConRecargo() {
        catalogoC1C3();
        escrituraLista(Map.of());
        conRecargo(MedioPago.CREDITO, "10.00");

        VentaRegistrada venta = comoVendedor(ventaC1C3ConCredito10("4909.66"), USUARIO);

        assertThat(venta.comprobante().getTotal()).isEqualByComparingTo("4909.66");
        assertThat(venta.comprobante().getRecargo()).isEqualByComparingTo("446.33");
        assertThat(venta.comprobante().getSubtotal()).isEqualByComparingTo("4463.33");
        assertThat(venta.items()).extracting(ItemComprobante::getRecargo)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("363.00"), bd("83.33"));
        assertThat(venta.items()).extracting(ItemComprobante::getTotal)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("3993.00"), bd("916.66"));
        assertThat(venta.pagos()).hasSize(1);
        Pago pago = venta.pagos().get(0);
        assertThat(pago.getFormaPagoId()).isEqualTo(formaId(MedioPago.CREDITO));
        assertThat(pago.getFormaPagoNombre()).isEqualTo("Crédito");
        assertThat(pago.getMedio()).isEqualTo(MedioPago.CREDITO);
        assertThat(pago.getRecargoPorcentaje()).isEqualByComparingTo("10.00");
        assertThat(pago.getRecargo()).isEqualByComparingTo("446.33");
        assertThat(pago.getMonto()).isEqualByComparingTo("4909.66");
        assertThat(pago.getParte()).isEqualByComparingTo("4463.33");
    }

    @Test
    @DisplayName("el total sin el recargo es un 409 con el total que incluye el recargo")
    void totalSinRecargoEsPreciosCambiados() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        conRecargo(MedioPago.CREDITO, "10.00");

        VentaRechazadaException e = rechazo(ventaC1C3ConCredito10("4463.33"));

        assertThat(e.motivo()).isEqualTo(Motivo.PRECIOS_CAMBIADOS);
        assertThat(e.total()).isEqualByComparingTo("4909.66");
        assertThat(e.articulos()).isEmpty();
        assertSinEscrituras();
    }

    @Test
    @DisplayName("en el pago mixto el recargo va solo sobre la parte de la forma que lo tiene")
    void pagoMixtoConRecargoEnUnaParte() {
        catalogoC1C3();
        escrituraLista(Map.of());
        conRecargo(MedioPago.CREDITO, "10.00");
        ConfirmarVenta pedido = new ConfirmarVenta(CLAVE,
                List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")), bd("4809.66"), null,
                BigDecimal.ZERO, List.of(pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.CREDITO, "3463.33")));

        VentaRegistrada venta = comoVendedor(pedido, USUARIO);

        assertThat(venta.comprobante().getTotal()).isEqualByComparingTo("4809.66");
        assertThat(venta.comprobante().getRecargo()).isEqualByComparingTo("346.33");
        assertThat(venta.pagos()).extracting(Pago::getMonto).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("1000.00"), bd("3809.66"));
        assertThat(venta.pagos()).extracting(Pago::getRecargo).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("0.00"), bd("346.33"));
        assertThat(venta.items().stream().map(ItemComprobante::getRecargo).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("346.33");
    }

    @Test
    @DisplayName("las partes que no suman el total sin recargo dan 400 en pagos, aunque el recargo cambie el total")
    void partesQueNoSumanConRecargo() {
        catalogoC1C3();
        conRecargo(MedioPago.CREDITO, "10.00");
        ConfirmarVenta pedido = new ConfirmarVenta(CLAVE,
                List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")), bd("4763.33"), null,
                BigDecimal.ZERO, List.of(pagoDe(MedioPago.EFECTIVO, "1000.00"), pagoDe(MedioPago.CREDITO, "3000.00")));

        assertThatThrownBy(() -> comoVendedor(pedido, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos", "Los pagos tienen que sumar el total de la venta."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("una forma que no existe, es de otra ferreteria o esta inactiva da 400 «no es válida» en su campo")
    void formaNoValida() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        ConfirmarVenta inexistente = new ConfirmarVenta(CLAVE,
                List.of(renglon(1L, "3", "1210.00"), renglon(2L, "2.5", "333.33")), bd("4463.33"), 999L);
        assertThatThrownBy(() -> comoVendedor(inexistente, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("formaPagoId", "La forma de pago no es válida."));

        formasEnLaBase.put(formaId(MedioPago.DEBITO), forma(MedioPago.DEBITO, "0.00", false));
        assertThatThrownBy(() -> comoVendedor(ventaC1C3(MedioPago.DEBITO), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("formaPagoId", "La forma de pago no es válida."));

        ConfirmarVenta enLaLista = ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "1000.00"),
                new ConfirmarVenta.PagoPedido(999L, bd("3463.33")));
        assertThatThrownBy(() -> comoVendedor(enLaLista, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("pagos[1].formaPagoId", "La forma de pago no es válida."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un total que con el recargo pasa el maximo da 400 en totalMostrado, no un 409 sin salida")
    void totalConRecargoMuyGrande() {
        catalogoDevuelve(vendible(1L, "Oro", "UNIDAD", "9000000000000.00", true));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        conRecargo(MedioPago.CREDITO, "200.00");

        assertThatThrownBy(() -> comoVendedor(pedido("9000000000000.00", MedioPago.CREDITO,
                renglon(1L, "1", "9000000000000.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsKey("totalMostrado"));
        assertSinEscrituras();
    }

    /** La venta C1 + C3 ya guardada con credito al 10 %: recargo 446,33, total 4.909,66. */
    void ventaYaGuardadaConCredito() {
        List<RenglonVenta> sinRecargo = List.of(RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")),
                RenglonVenta.de(bd("333.33"), bd("2.5"), bd("21.00")));
        List<RenglonVenta> repartidos = com.ferreteria.ventas.model.RecargoVenta.repartir(sinRecargo, bd("446.33"));
        Comprobante c = Comprobante.venta(FERRETERIA, NUMERO, CLAVE, USUARIO, TotalesVenta.de(repartidos), CAJA, FECHA);
        ReflectionTestUtils.setField(c, "id", COMPROBANTE);
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(c));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(
                ItemComprobante.de(FERRETERIA, COMPROBANTE, 1, 1L, "Tornillo 1/4", "UNIDAD", "IVA_21", null, repartidos.get(0)),
                ItemComprobante.de(FERRETERIA, COMPROBANTE, 2, 2L, "Cable 2,5 mm", "METRO", "IVA_21", null,
                        repartidos.get(1))));
        conRecargo(MedioPago.CREDITO, "10.00");
        when(pagoRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(
                Pago.de(FERRETERIA, COMPROBANTE, formasEnLaBase.get(formaId(MedioPago.CREDITO)), bd("4463.33"),
                        bd("446.33"))));
    }

    @Test
    @DisplayName("repetir una venta con recargo: mismo pedido es la misma venta; otra forma o el total sin recargo, CLAVE_CON_OTRO_CONTENIDO")
    void repetidaConRecargo() {
        ventaYaGuardadaConCredito();

        VentaRegistrada igual = comoVendedor(ventaC1C3ConCredito10("4909.66"), USUARIO);
        assertThat(igual.repetida()).isTrue();

        // el cobro entre dos pagos de la misma venta cuenta por la parte sin recargo: otra forma es otro contenido
        for (ConfirmarVenta otro : List.of(ventaC1C3(MedioPago.DEBITO), ventaC1C3ConCredito10("4463.33"))) {
            assertThat(rechazo(otro).motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO);
        }
        assertNadaNuevo();
    }

    @Test
    @DisplayName("repetir una venta cuya forma se desactivo despues devuelve la venta original: ya esta confirmada")
    void repetidaConLaFormaDesactivada() {
        ventaYaGuardadaConCredito();
        formasEnLaBase.put(formaId(MedioPago.CREDITO), forma(MedioPago.CREDITO, "10.00", false));

        assertThat(comoVendedor(ventaC1C3ConCredito10("4909.66"), USUARIO).repetida()).isTrue();
    }

    // ---------------------------------------------------------------- cuenta corriente (CC-005 a CC-015)

    static final Long CLIENTE = 5L;
    static final Long OTRA_FORMA_CC = 50L;

    /** El cliente 5, activo, que toma la venta bajo lock. */
    void clienteTomado() {
        when(clientesParaVenta.tomarParaVenta(CLIENTE)).thenReturn(Optional.of(
                new com.ferreteria.clientes.api.ClienteReferencia(CLIENTE, "Ferretería Norte SRL", true,
                        com.ferreteria.clientes.api.ClienteReferencia.CondicionIva.CONSUMIDOR_FINAL, null, null, null)));
    }

    /** El cupo de la cuenta del cliente 5 para el importe de la venta: limite, saldo y exceso dados. */
    void cupoDe(String importe, String limite, String saldo) {
        BigDecimal exceso = bd(saldo).add(bd(importe)).subtract(bd(limite));
        when(cuentaCorriente.verificarCupo(CLIENTE, bd(importe))).thenReturn(new CuentaCorrienteParaVenta.CupoCuenta(
                true, bd(limite), bd(saldo), bd(limite).subtract(bd(saldo)),
                exceso.signum() > 0 ? exceso : BigDecimal.ZERO));
    }

    ConfirmarVenta ventaCC() {
        return conCliente(ventaC1C3(MedioPago.CUENTA_CORRIENTE), CLIENTE);
    }

    static ConfirmarVenta autorizando(ConfirmarVenta p) {
        return new ConfirmarVenta(p.clave(), p.renglones(), p.totalMostrado(), p.formaPagoId(), p.descuentoPorcentaje(),
                p.pagos(), p.presupuestoId(), p.remitoId(), p.facturable(), p.clienteId(), true);
    }

    ConfirmarVenta.PagoPedido pagoDe(Long formaId, String monto) {
        return new ConfirmarVenta.PagoPedido(formaId, bd(monto));
    }

    CuentaCorrienteParaVenta.AsientoVenta asientoGuardado() {
        ArgumentCaptor<CuentaCorrienteParaVenta.AsientoVenta> captor =
                ArgumentCaptor.forClass(CuentaCorrienteParaVenta.AsientoVenta.class);
        verify(cuentaCorriente).asentarVenta(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("el orden de locks: caja, cliente (antes de validar) y, despues de verificar el cupo, numerador ... asiento al final")
    void ordenDeLocksConCliente() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "10000.00", "0.00");

        comoVendedor(ventaCC(), USUARIO);

        InOrder orden = inOrder(cajaService, clientesParaVenta, articulosParaVenta, cuentaCorriente, numeradorRepository,
                comprobanteRepository, stockService, itemRepository, pagoRepository);
        orden.verify(cajaService).cajaParaVender();
        orden.verify(clientesParaVenta).tomarParaVenta(CLIENTE);
        orden.verify(articulosParaVenta).buscar(anyCollection());
        orden.verify(cuentaCorriente).verificarCupo(CLIENTE, bd("4463.33"));
        orden.verify(numeradorRepository).siguienteScoped();
        orden.verify(comprobanteRepository).save(any(Comprobante.class));
        orden.verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), any());
        orden.verify(itemRepository).saveAll(any());
        orden.verify(pagoRepository).save(any(Pago.class));
        orden.verify(cuentaCorriente).asentarVenta(any());
    }

    @Test
    @DisplayName("con factura: decidir va despues del cliente y antes del numerador, y el registro fiscal es el ultimo hijo, con el receptor, lo cobrado por renglon y el instante de la venta")
    void ordenDeLocksConFactura() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "10000.00", "0.00");
        when(facturacion.decidir(any())).thenReturn(new Decision(true, true, bd("10000000.00")));

        VentaRegistrada venta = comoVendedor(ventaCC(), USUARIO);

        InOrder orden = inOrder(clientesParaVenta, facturacion, cuentaCorriente, numeradorRepository, comprobanteRepository,
                stockService, itemRepository, pagoRepository);
        orden.verify(clientesParaVenta).tomarParaVenta(CLIENTE);
        orden.verify(facturacion).decidir(new ReceptorVenta(FacturacionParaVenta.CondicionIva.CONSUMIDOR_FINAL, null,
                null, "Ferretería Norte SRL", null));
        orden.verify(cuentaCorriente).verificarCupo(CLIENTE, bd("4463.33"));
        orden.verify(numeradorRepository).siguienteScoped();
        orden.verify(comprobanteRepository).save(any(Comprobante.class));
        orden.verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), eq(FECHA), any());
        orden.verify(itemRepository).saveAll(any());
        orden.verify(pagoRepository).save(any(Pago.class));
        orden.verify(cuentaCorriente).asentarVenta(any());
        orden.verify(facturacion).registrar(COMPROBANTE, new ReceptorVenta(FacturacionParaVenta.CondicionIva.CONSUMIDOR_FINAL,
                null, null, "Ferretería Norte SRL", null), List.of(new RenglonParaFacturar(bd("21.00"), bd("3630.00")),
                new RenglonParaFacturar(bd("21.00"), bd("833.33"))), RELOJ.instant());
        assertThat(venta.comprobante().getFecha()).isEqualTo(FECHA);
        assertThat(venta.conRegistroFiscal()).isTrue();
    }

    @Test
    @DisplayName("la venta no facturable no pregunta si factura ni da de alta un registro fiscal")
    void laNoFacturableNoPreguntaAFacturacion() {
        catalogoC1C3();
        escrituraLista(Map.of());
        ConfirmarVenta base = ventaC1C3(MedioPago.EFECTIVO);
        ConfirmarVenta noFacturable = new ConfirmarVenta(base.clave(), base.renglones(), base.totalMostrado(),
                base.formaPagoId(), base.descuentoPorcentaje(), base.pagos(), null, null, false, null);

        VentaRegistrada venta = comoVendedor(noFacturable, USUARIO);

        verifyNoInteractions(facturacion);
        assertThat(venta.conRegistroFiscal()).isFalse();
        assertThat(venta.comprobante().getEstado()).isEqualTo(EstadoComprobante.NO_FACTURABLE);
    }

    @Test
    @DisplayName("la venta facturable de una ferreteria que no factura pregunta, pero no da de alta un registro fiscal")
    void decidirSinFacturaNoRegistra() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        verify(facturacion).decidir(ReceptorVenta.sinCliente());
        verify(facturacion, never()).registrar(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        assertThat(venta.conRegistroFiscal()).isFalse();
    }

    @Test
    @DisplayName("el orden de locks de una venta desde un presupuesto: caja, presupuesto, cliente, numerador")
    void ordenDeLocksDesdePresupuesto() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "10000.00", "0.00");
        com.ferreteria.ventas.model.Presupuesto presupuesto = org.mockito.Mockito.mock(com.ferreteria.ventas.model.Presupuesto.class);
        when(presupuestoRepository.paraCambiarScoped(8L)).thenReturn(Optional.of(presupuesto));
        ConfirmarVenta base = ventaCC();
        ConfirmarVenta desdePresupuesto = new ConfirmarVenta(base.clave(), base.renglones(), base.totalMostrado(),
                base.formaPagoId(), base.descuentoPorcentaje(), base.pagos(), 8L, null, true, CLIENTE);

        comoVendedor(desdePresupuesto, USUARIO);

        InOrder orden = inOrder(cajaService, presupuestoRepository, clientesParaVenta, numeradorRepository);
        orden.verify(cajaService).cajaParaVender();
        orden.verify(presupuestoRepository).paraCambiarScoped(8L);
        orden.verify(clientesParaVenta).tomarParaVenta(CLIENTE);
        orden.verify(numeradorRepository).siguienteScoped();
    }

    @Test
    @DisplayName("el orden de locks de una venta desde un remito: caja, remito, cliente, numerador")
    void ordenDeLocksDesdeRemito() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        when(numeradorRepository.siguienteScoped()).thenReturn(NUMERO);
        when(comprobanteRepository.save(any(Comprobante.class))).thenAnswer(inv -> {
            Comprobante c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", COMPROBANTE);
            return c;
        });
        when(itemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pagoRepository.save(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 100L);
            return p;
        });
        clienteTomado();
        cupoDe("4463.33", "10000.00", "0.00");
        com.ferreteria.ventas.model.Remito remito = org.mockito.Mockito.mock(com.ferreteria.ventas.model.Remito.class);
        when(remitoRepository.paraCambiarScoped(8L)).thenReturn(Optional.of(remito));
        ConfirmarVenta base = ventaCC();
        ConfirmarVenta desdeRemito = new ConfirmarVenta(base.clave(), base.renglones(), base.totalMostrado(),
                base.formaPagoId(), base.descuentoPorcentaje(), base.pagos(), null, 8L, true, CLIENTE);

        comoVendedor(desdeRemito, USUARIO);

        InOrder orden = inOrder(cajaService, remitoRepository, clientesParaVenta, numeradorRepository);
        orden.verify(cajaService).cajaParaVender();
        orden.verify(remitoRepository).paraCambiarScoped(8L);
        orden.verify(clientesParaVenta).tomarParaVenta(CLIENTE);
        orden.verify(numeradorRepository).siguienteScoped();
    }

    @Test
    @DisplayName("una venta con cliente que NO cobra a cuenta toma al cliente igual (mismo lugar del orden) y no mira la cuenta")
    void ventaConClienteSinCuentaTomaAlCliente() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();

        comoVendedor(conCliente(ventaC1C3(MedioPago.EFECTIVO), CLIENTE), USUARIO);

        InOrder orden = inOrder(clientesParaVenta, numeradorRepository);
        orden.verify(clientesParaVenta).tomarParaVenta(CLIENTE);
        orden.verify(numeradorRepository).siguienteScoped();
        verifyNoInteractions(cuentaCorriente);
    }

    @Test
    @DisplayName("sin cuenta corriente y sin cliente la venta de siempre no toca el puerto de clientes ni el de cuenta")
    void ventaSinClienteNiCuenta() {
        catalogoC1C3();
        escrituraLista(Map.of());

        comoVendedor(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        verifyNoInteractions(cuentaCorriente, clientesParaVenta);
    }

    @Test
    @DisplayName("pagar a cuenta corriente sin elegir cliente es 400 en clienteId con el mensaje exacto, y no escribe nada")
    void cuentaSinCliente() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comoVendedor(ventaC1C3(MedioPago.CUENTA_CORRIENTE), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("clienteId", "Elegí el cliente para vender a cuenta corriente."));
        assertSinEscrituras();
        verifyNoInteractions(cuentaCorriente);
    }

    @Test
    @DisplayName("en un pago mixto con una parte a cuenta y sin cliente también es 400 en clienteId")
    void mixtoConCuentaSinCliente() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comoVendedor(ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "1000.00"),
                pagoDe(MedioPago.CUENTA_CORRIENTE, "3463.33")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsEntry("clienteId", "Elegí el cliente para vender a cuenta corriente."));
        assertSinEscrituras();
    }

    @Test
    @DisplayName("el 409 de precios gana al 400 de cliente obligatorio (CC-014): primero se corrige el ticket")
    void preciosGananAlClienteObligatorio() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        ConfirmarVenta conOtroTotal = pedido("1.00", MedioPago.CUENTA_CORRIENTE, renglon(1L, "3", "1210.00"),
                renglon(2L, "2.5", "333.33"));

        assertThat(rechazo(conOtroTotal).motivo()).isEqualTo(Motivo.PRECIOS_CAMBIADOS);
    }

    @Test
    @DisplayName("el importe a verificar es solo la parte a cuenta del pago mixto, sumada sobre todos los pagos a cuenta")
    void importeEsLaParteACuenta() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        formasEnLaBase.put(OTRA_FORMA_CC, formaConId(MedioPago.CUENTA_CORRIENTE, OTRA_FORMA_CC));
        cupoDe("3463.33", "10000.00", "0.00");

        comoVendedor(conCliente(ventaC1C3Mixta(pagoDe(MedioPago.EFECTIVO, "1000.00"),
                pagoDe(MedioPago.CUENTA_CORRIENTE, "2000.00"), pagoDe(OTRA_FORMA_CC, "1463.33")), CLIENTE), USUARIO);

        verify(cuentaCorriente).verificarCupo(CLIENTE, bd("3463.33"));
        CuentaCorrienteParaVenta.AsientoVenta asiento = asientoGuardado();
        assertThat(asiento.pagos()).extracting(CuentaCorrienteParaVenta.CargoPago::monto)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("2000.00"), bd("1463.33"));
    }

    FormaPago formaConId(MedioPago medio, Long id) {
        FormaPago forma = FormaPago.de(FERRETERIA, "Otra " + nombreDe(medio), medio, bd("0.00"), true, 9);
        ReflectionTestUtils.setField(forma, "id", id);
        return forma;
    }

    @Test
    @DisplayName("la venta a cuenta que alcanza asienta UN cargo por pago con su id, el comprobante, el cliente y el usuario, sin autorizado")
    void asientaLaVenta() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "0.00");

        VentaRegistrada venta = comoVendedor(ventaCC(), USUARIO);

        CuentaCorrienteParaVenta.AsientoVenta asiento = asientoGuardado();
        assertThat(asiento.clienteId()).isEqualTo(CLIENTE);
        assertThat(asiento.comprobanteId()).isEqualTo(COMPROBANTE);
        assertThat(asiento.comprobanteNumero()).isEqualTo(NUMERO);
        assertThat(asiento.usuarioId()).isEqualTo(USUARIO);
        assertThat(asiento.autorizadoPor()).isNull();
        assertThat(asiento.pagos()).hasSize(1);
        assertThat(asiento.pagos().get(0).pagoId()).isEqualTo(venta.pagos().get(0).getId()).isEqualTo(100L);
        assertThat(asiento.pagos().get(0).monto()).isEqualByComparingTo("4463.33");
    }

    @Test
    @DisplayName("justo en el límite no excede: pasa sin autorización")
    void justoEnElLimite() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "536.67");

        comoVendedor(ventaCC(), USUARIO);

        assertThat(asientoGuardado().autorizadoPor()).isNull();
    }

    @Test
    @DisplayName("una venta NO facturable a cuenta suma deuda igual que una facturable")
    void noFacturableSumaDeuda() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "0.00");

        comoVendedor(noFacturable(ventaCC()), USUARIO);

        verify(cuentaCorriente).asentarVenta(any());
    }

    @Test
    @DisplayName("sin cuenta habilitada: CUENTA_CORRIENTE_NO_HABILITADA (422) con su razón social, también para el Dueño y con la autorización")
    void noHabilitada() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        clienteTomado();
        when(cuentaCorriente.verificarCupo(CLIENTE, bd("4463.33"))).thenReturn(new CuentaCorrienteParaVenta.CupoCuenta(
                false, null, bd("0.00"), null, BigDecimal.ZERO));

        VentaRechazadaException vendedor = rechazo(ventaCC());
        Throwable dueno = org.assertj.core.api.Assertions.catchThrowable(
                () -> registro.registrar(autorizando(ventaCC()), USUARIO, true));

        assertThat(vendedor.motivo()).isEqualTo(Motivo.CUENTA_CORRIENTE_NO_HABILITADA);
        assertThat(vendedor.motivo().estadoHttp()).isEqualTo(422);
        assertThat(vendedor.getMessage()).isEqualTo("Ferretería Norte SRL no tiene cuenta corriente habilitada.");
        assertThat(dueno).isInstanceOfSatisfying(VentaRechazadaException.class,
                e -> assertThat(e.motivo()).isEqualTo(Motivo.CUENTA_CORRIENTE_NO_HABILITADA));
        assertSinEscrituras();
        verify(cuentaCorriente, never()).asentarVenta(any());
    }

    @Test
    @DisplayName("el exceso sin autorización del Vendedor es LIMITE (422) sin cifras y puedeAutorizar=false; no guarda nada")
    void excesoVendedorSinFlag() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "4000.00");

        VentaRechazadaException e = rechazo(ventaCC());

        assertThat(e.motivo()).isEqualTo(Motivo.LIMITE_CUENTA_CORRIENTE);
        assertThat(e.motivo().estadoHttp()).isEqualTo(422);
        assertThat(e.excesoLimite()).isEqualTo(VentaRechazadaException.ExcesoLimite.sinCifras());
        assertThat(e.getMessage()).contains("Pedile al Dueño");
        assertSinEscrituras();
        verify(cuentaCorriente, never()).asentarVenta(any());
    }

    @Test
    @DisplayName("el exceso sin autorizar del Dueño es LIMITE (422) con puedeAutorizar y las cifras (saldo, límite, disponible, importe, exceso)")
    void excesoDuenoSinFlag() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "4000.00");

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> registro.registrar(ventaCC(), USUARIO, true));

        assertThat(error).isInstanceOfSatisfying(VentaRechazadaException.class, e -> {
            assertThat(e.motivo()).isEqualTo(Motivo.LIMITE_CUENTA_CORRIENTE);
            assertThat(e.getMessage()).contains("Autorizala para seguir");
            VentaRechazadaException.ExcesoLimite exceso = e.excesoLimite();
            assertThat(exceso.puedeAutorizar()).isTrue();
            assertThat(exceso.saldo()).isEqualByComparingTo("4000.00");
            assertThat(exceso.limite()).isEqualByComparingTo("5000.00");
            assertThat(exceso.disponible()).isEqualByComparingTo("1000.00");
            assertThat(exceso.importe()).isEqualByComparingTo("4463.33");
            assertThat(exceso.exceso()).isEqualByComparingTo("3463.33");
        });
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un Vendedor que manda la autorización y excede recibe AccessDenied (403) y no se guarda nada")
    void vendedorAutorizaConExceso() {
        catalogoC1C3();
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "4000.00");

        assertThatThrownBy(() -> comoVendedor(autorizando(ventaCC()), USUARIO))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("Autorizar el exceso es solo del Dueño");
        assertSinEscrituras();
        verify(cuentaCorriente, never()).asentarVenta(any());
    }

    @Test
    @DisplayName("el Dueño que autoriza un exceso vende, y el asiento lleva su id en autorizadoPor")
    void duenoAutorizaElExceso() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "4000.00");

        VentaRegistrada venta = registro.registrar(autorizando(ventaCC()), USUARIO, true);

        assertThat(venta.repetida()).isFalse();
        assertThat(asientoGuardado().autorizadoPor()).isEqualTo(USUARIO);
    }

    @Test
    @DisplayName("la autorización del Dueño sin exceso se ignora: no ensucia el libro (autorizadoPor null)")
    void autorizacionDelDuenoSinExceso() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "0.00");

        registro.registrar(autorizando(ventaCC()), USUARIO, true);

        assertThat(asientoGuardado().autorizadoPor()).isNull();
    }

    @Test
    @DisplayName("la autorización del Vendedor sin exceso también se ignora: no es 403 si la venta no excede")
    void autorizacionDelVendedorSinExceso() {
        catalogoC1C3();
        escrituraLista(Map.of());
        clienteTomado();
        cupoDe("4463.33", "5000.00", "0.00");

        comoVendedor(autorizando(ventaCC()), USUARIO);

        assertThat(asientoGuardado().autorizadoPor()).isNull();
    }

    @Test
    @DisplayName("repetir una venta a cuenta devuelve la original sin tomar al cliente ni asentar de nuevo, aunque cambie la autorización")
    void repetidaNoTocaLaCuenta() {
        Comprobante guardado = ventaGuardadaC1C3();
        ReflectionTestUtils.setField(guardado, "clienteId", CLIENTE);
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(guardado));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(itemsGuardadosC1C3());
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(pagoGuardado(MedioPago.CUENTA_CORRIENTE, "4463.33")));

        assertThat(comoVendedor(ventaCC(), USUARIO).repetida()).isTrue();
        assertThat(registro.registrar(autorizando(ventaCC()), USUARIO, true).repetida()).isTrue();

        verifyNoInteractions(cuentaCorriente, clientesParaVenta);
        assertNadaNuevo();
    }
}
