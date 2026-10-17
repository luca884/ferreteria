package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Pago;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.RegistroVenta;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import java.math.BigDecimal;
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

    RegistroVenta registro;

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        registro = new RegistroVenta(
                articulosParaVenta, stockService, comprobanteRepository, itemRepository, pagoRepository,
                numeradorRepository);
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
                bd("21.00"), activo);
    }

    static ArticuloVendible tornillo(Long id, String precio) {
        return vendible(id, "Tornillo 1/4", "UNIDAD", precio, true);
    }

    static Renglon renglon(Long articuloId, String cantidad, String precio) {
        return new Renglon(articuloId, bd(cantidad), bd(precio));
    }

    static ConfirmarVenta pedido(String total, MedioPago medio, Renglon... renglones) {
        return new ConfirmarVenta(CLAVE, List.of(renglones), bd(total), medio);
    }

    void catalogoDevuelve(ArticuloVendible... articulos) {
        when(articulosParaVenta.buscar(anyCollection())).thenReturn(List.of(articulos));
    }

    /** Ningun rechazo escribe nada: ni numero, ni stock, ni comprobante, items o pago. */
    void assertSinEscrituras() {
        verifyNoInteractions(numeradorRepository, stockService, itemRepository, pagoRepository);
        org.mockito.Mockito.verify(comprobanteRepository, org.mockito.Mockito.never()).save(any());
    }

    VentaRechazadaException rechazo(ConfirmarVenta pedido) {
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> registro.registrar(pedido, USUARIO));
        assertThat(error).isInstanceOf(VentaRechazadaException.class);
        return (VentaRechazadaException) error;
    }

    // ---------------------------------------------------------------- T-12: validaciones

    @Test
    @DisplayName("sin ferreteria en el contexto (super admin) falla antes de tocar nada")
    void sinFerreteria() {
        TenantContext.clear();

        assertThatThrownBy(() -> registro.registrar(pedido("10.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00")),
                USUARIO)).isInstanceOf(MissingTenantException.class);
        verifyNoInteractions(articulosParaVenta);
        assertSinEscrituras();
    }

    @Test
    @DisplayName("un articuloId repetido da 400 y no consulta el catalogo")
    void articuloRepetido() {
        ConfirmarVenta pedido = pedido("20.00", MedioPago.EFECTIVO, renglon(1L, "1", "10.00"),
                renglon(2L, "1", "10.00"), renglon(1L, "1", "10.00"));

        assertThatThrownBy(() -> registro.registrar(pedido, USUARIO))
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
        assertThatThrownBy(() -> registro.registrar(
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

        assertThatThrownBy(() -> registro.registrar(pedido("25.00", MedioPago.EFECTIVO, renglon(1L, "2.5", "10.00")),
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

        assertThatThrownBy(() -> registro.registrar(pedido("0.00", MedioPago.EFECTIVO, renglon(1L, "0", "10.00"),
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
        assertThatThrownBy(() -> registro.registrar(pedido("10", null, renglon(1L, "1", "10")), USUARIO))
                .isInstanceOf(DatosInvalidosException.class);
    }

    @Test
    @DisplayName("sin medio de pago da 400 en el campo medioPago")
    void sinMedioDePago() {
        catalogoDevuelve(tornillo(1L, "10.00"));
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registro.registrar(pedido("10.00", null, renglon(1L, "1", "10.00")), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsKey("medioPago"));
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
        assertThatThrownBy(() -> registro.registrar(pedido("1.00", null, renglon(2L, "2.5", "99.00")), USUARIO))
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
        when(stockService.registrarVenta(eq(COMPROBANTE), eq(USUARIO), any())).thenReturn(stockResultante);
        when(itemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pagoRepository.save(any(Pago.class))).thenAnswer(inv -> inv.getArgument(0));
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

        registro.registrar(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        InOrder orden = inOrder(numeradorRepository, comprobanteRepository, stockService, itemRepository,
                pagoRepository);
        orden.verify(numeradorRepository).siguienteScoped();
        orden.verify(comprobanteRepository).save(any(Comprobante.class));
        orden.verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), any());
        orden.verify(itemRepository).saveAll(any());
        orden.verify(pagoRepository).save(any(Pago.class));
    }

    @Test
    @DisplayName("la cabecera es VENTA SIN_FACTURAR con numero, clave, usuario y totales de los renglones")
    void cabecera() {
        catalogoC1C3();
        escrituraLista(Map.of());

        VentaRegistrada venta = registro.registrar(ventaC1C3(MedioPago.DEBITO), USUARIO);

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

    @Test
    @DisplayName("cada item copia descripcion, unidad, cantidad, precio, alicuota del servidor, total, neto e IVA")
    void itemsCopianLosDatos() {
        catalogoC1C3();
        escrituraLista(Map.of());

        registro.registrar(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

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

        VentaRegistrada venta = registro.registrar(ventaC1C3(MedioPago.TRANSFERENCIA), USUARIO);

        verify(pagoRepository, times(1)).save(any(Pago.class));
        Pago pago = venta.pago();
        assertThat(pago.getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(pago.getMonto()).isEqualByComparingTo("4463.33");
        assertThat(pago.getComprobanteId()).isEqualTo(COMPROBANTE);
        assertThat(pago.getFerreteriaId()).isEqualTo(FERRETERIA);
    }

    @Test
    @DisplayName("le pide al stock una salida por renglon, con la cantidad vendida")
    @SuppressWarnings("unchecked")
    void salidasDeStock() {
        catalogoC1C3();
        escrituraLista(Map.of());

        registro.registrar(ventaC1C3(MedioPago.EFECTIVO), USUARIO);

        ArgumentCaptor<List<SalidaStock>> salidas = ArgumentCaptor.forClass(List.class);
        verify(stockService).registrarVenta(eq(COMPROBANTE), eq(USUARIO), salidas.capture());
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

        VentaRegistrada venta = registro.registrar(pedido("110.00", MedioPago.EFECTIVO, renglon(1L, "5", "10.00"),
                renglon(2L, "5", "10.00"), renglon(3L, "1", "10.00")), USUARIO);

        assertThat(venta.sinStock()).hasSize(1);
        assertThat(venta.sinStock().get(0).articuloId()).isEqualTo(2L);
        assertThat(venta.sinStock().get(0).descripcion()).isEqualTo("Corto");
        assertThat(venta.sinStock().get(0).stockActual()).isEqualByComparingTo("-3");
    }

    // ---- clave repetida

    Comprobante ventaGuardada() {
        Comprobante c = Comprobante.venta(FERRETERIA, NUMERO, CLAVE, USUARIO,
                TotalesVenta.de(List.of(RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")))));
        ReflectionTestUtils.setField(c, "id", COMPROBANTE);
        return c;
    }

    ItemComprobante itemGuardado() {
        return ItemComprobante.de(FERRETERIA, COMPROBANTE, 1, 1L, "Tornillo 1/4", "UNIDAD", "IVA_21",
                RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00")));
    }

    void ventaYaRegistrada() {
        when(comprobanteRepository.porClaveScoped(CLAVE)).thenReturn(Optional.of(ventaGuardada()));
        when(itemRepository.deComprobanteScoped(COMPROBANTE)).thenReturn(List.of(itemGuardado()));
        when(pagoRepository.deComprobanteScoped(COMPROBANTE))
                .thenReturn(List.of(Pago.de(FERRETERIA, COMPROBANTE, MedioPago.EFECTIVO, bd("3630.00"))));
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

        VentaRegistrada venta = registro.registrar(
                pedido("3630.00", MedioPago.EFECTIVO, renglon(1L, "3", "1210.00")), USUARIO);

        assertThat(venta.repetida()).isTrue();
        assertThat(venta.comprobante().getId()).isEqualTo(COMPROBANTE);
        assertThat(venta.comprobante().getNumero()).isEqualTo(NUMERO);
        assertThat(venta.items()).hasSize(1);
        assertThat(venta.pago().getMedio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(venta.sinStock()).isEmpty();
        assertNadaNuevo();
    }

    @Test
    @DisplayName("misma clave y mismo contenido con otra escala de los numeros sigue siendo la misma venta")
    void repetidaOtraEscala() {
        ventaYaRegistrada();

        VentaRegistrada venta = registro.registrar(
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
}
