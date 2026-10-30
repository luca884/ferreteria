package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cobertura de los lectores de la venta con la nota de credito (CU-08, cu-08-nota-de-credito T5: S5, S7, S12 y S14; C4): una
 * venta y su NC, y cada lector que antes leia solo {@code tipo = VENTA} tiene que tratarla como C4. Si un lector nuevo o
 * viejo se olvida de la NC, uno de estos tests lo muestra.
 *
 * <p>El escenario: ayer el Vendedor le vendio a Ana 2 Tornillos (rubro Bulones, costo 40) con 10 % de descuento, en efectivo;
 * hoy el Dueño la anula con la NC (armada con la fabrica de C3, como la va a crear T4) y el stock vuelve. Otra ferreteria
 * tiene su propia venta, sin nota.
 */
class NotaDeCreditoEnLosLectoresIntegrationTest extends VentasTestBase {

    private static final String REPORTES = "/api/reportes/ventas/";

    @Autowired
    private ComprobanteRepository comprobantes;

    @Autowired
    private ItemComprobanteRepository items;

    @Autowired
    private NumeradorComprobanteRepository numerador;

    @Autowired
    private PlatformTransactionManager transacciones;

    private Tienda t;
    private String dueno;
    private long tornillo;
    private long ana;
    private long venta;
    private long ventaNumero;
    private long nota;
    private long notaNumero;
    private LocalDate hoy;
    private LocalDate ayer;

    @BeforeEach
    void escenario() throws Exception {
        hoy = HoraArgentina.hoy();
        ayer = hoy.minusDays(1);
        t = tienda();
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, 'Bulones', 30.00)", t.id());
        long bulones = jdbc.queryForObject("select id from rubro where ferreteria_id = ?", Long.class, t.id());
        tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        jdbc.update("update articulo set costo = 40.00, rubro_id = ? where id = ?", bulones, tornillo);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, 'Ana Ferrari')", t.id());
        ana = jdbc.queryForObject("select id from cliente where ferreteria_id = ?", Long.class, t.id());
        dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());

        Map<String, Object> cuerpo = mapa(clave(), null, "180.00", linea(tornillo, "2", "100.00"));
        cuerpo.put("formaPagoId", formaDePago(vendedor, "EFECTIVO"));
        cuerpo.put("descuentoPorcentaje", new BigDecimal("10.00"));
        cuerpo.put("clienteId", ana);
        JsonNode vendida = json(postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));
        venta = vendida.get("ventaId").asLong();
        ventaNumero = vendida.get("numero").asLong();
        fechar(venta, ayer.atTime(10, 0));
        anular(venta, null);
    }

    /**
     * La NC de la venta, hoy, del Dueño, como la va a crear T4: el numerador de siempre, la fabrica de C3, los renglones
     * copiados y el movimiento de stock NOTA_CREDITO que repone lo vendido.
     */
    private void anular(long ventaId, String motivo) {
        long duenoId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, t.emailDueno());
        TenantContext.setCurrentFerreteriaId(t.id());
        try {
            new TransactionTemplate(transacciones).executeWithoutResult(s -> {
                Comprobante vendida = comprobantes.findScoped(ventaId).orElseThrow();
                Comprobante creada = comprobantes.save(Comprobante.notaDeCredito(vendida, numerador.siguienteScoped(),
                        clave(), duenoId, HoraArgentina.ahora(), motivo));
                for (ItemComprobante renglon : items.deComprobanteScoped(ventaId)) {
                    items.save(ItemComprobante.copiaParaLaNota(renglon, creada.getId()));
                    jdbc.update("insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, comprobante_id, "
                            + "usuario_id, fecha) values (?, ?, 'NOTA_CREDITO', ?, ?, ?, ?)", t.id(),
                            renglon.getArticuloId(), renglon.getCantidad(), creada.getId(), duenoId,
                            comoLaGuardaHibernate(creada.getFecha()));
                    jdbc.update("update articulo set stock_actual = stock_actual + ? where id = ?", renglon.getCantidad(),
                            renglon.getArticuloId());
                }
                nota = creada.getId();
                notaNumero = creada.getNumero();
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** La fecha como la guarda Hibernate ({@code jdbc.time_zone: UTC}): la hora de Buenos Aires pasada a UTC. */
    private static LocalDateTime comoLaGuardaHibernate(LocalDateTime hora) {
        return hora.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private void fechar(long comprobante, LocalDateTime hora) {
        jdbc.update("update comprobante set fecha = ? where id = ?", comoLaGuardaHibernate(hora), comprobante);
    }

    private BigDecimal columna(String columna, long comprobante) {
        return jdbc.queryForObject("select " + columna + " from comprobante where id = ?", BigDecimal.class, comprobante);
    }

    private JsonNode reporte(String nombre, LocalDate desde, LocalDate hasta) throws Exception {
        return getJson(REPORTES + nombre + "?desde=" + desde + "&hasta=" + hasta, dueno);
    }

    private static JsonNode fila(JsonNode reporte, String campo, String valor) {
        for (JsonNode fila : reporte.get("filas")) {
            if (fila.get(campo).asText().equals(valor)) {
                return fila;
            }
        }
        throw new AssertionError("No hay fila con " + campo + " = " + valor + " en " + reporte);
    }

    @Test
    @DisplayName("resumen: con la venta y su NC el total, el neto, el IVA, el costo y la ganancia dan cero; muestra la NC y "
            + "la ganancia sin NC")
    void resumenConLaVentaYSuNota() throws Exception {
        BigDecimal ganancia = columna("neto", venta).subtract(new BigDecimal("80.00"));

        JsonNode r = reporte("resumen", ayer, hoy);

        assertThat(r.get("cantidad").asInt()).isEqualTo(1);
        assertThat(r.get("total").decimalValue()).isZero();
        assertThat(r.get("neto").decimalValue()).isZero();
        assertThat(r.get("iva").decimalValue()).isZero();
        assertThat(r.get("costo").decimalValue()).isZero();
        assertThat(r.get("ganancia").decimalValue()).isZero();
        assertThat(r.get("gananciaSinNotas").decimalValue()).isEqualByComparingTo(ganancia);
        assertThat(r.get("totalSinNotas").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(r.get("notasDeCredito").get("cantidad").asInt()).isEqualTo(1);
        assertThat(r.get("notasDeCredito").get("total").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(r.get("ventasSinCosto").asInt()).isZero();
    }

    @Test
    @DisplayName("la NC resta en su fecha, no en la de la venta: ayer queda la venta y hoy solo la NC, en negativo")
    void laNotaRestaEnSuFecha() throws Exception {
        BigDecimal ganancia = columna("neto", venta).subtract(new BigDecimal("80.00"));

        JsonNode deAyer = reporte("resumen", ayer, ayer);
        JsonNode deHoy = reporte("resumen", hoy, hoy);

        assertThat(deAyer.get("total").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(deAyer.get("ganancia").decimalValue()).isEqualByComparingTo(ganancia);
        assertThat(deAyer.get("notasDeCredito").get("cantidad").asInt()).isZero();
        assertThat(deHoy.get("cantidad").asInt()).isZero();
        assertThat(deHoy.get("total").decimalValue()).isEqualByComparingTo("-180.00");
        assertThat(deHoy.get("ganancia").decimalValue()).isEqualByComparingTo(ganancia.negate());
        assertThat(deHoy.get("gananciaSinNotas").decimalValue()).isZero();
        assertThat(deHoy.get("totalSinNotas").decimalValue()).isZero();
        assertThat(deHoy.get("notasDeCredito").get("total").decimalValue()).isEqualByComparingTo("180.00");
        JsonNode dias = reporte("por-periodo", ayer, hoy);
        assertThat(fila(dias, "periodo", ayer.toString()).get("total").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(fila(dias, "periodo", hoy.toString()).get("total").decimalValue()).isEqualByComparingTo("-180.00");
        assertThat(fila(dias, "periodo", hoy.toString()).get("cantidad").asInt()).isZero();
    }

    @Test
    @DisplayName("por usuario: la venta es del Vendedor y la NC del Dueño, que la hizo")
    void porUsuarioLaNotaEsDeQuienLaHizo() throws Exception {
        JsonNode r = reporte("por-usuario", ayer, hoy);

        JsonNode vendedor = fila(r, "nombre", "Vendedor Ventas");
        JsonNode delDueno = fila(r, "nombre", "Dueno Ventas");
        assertThat(vendedor.get("cantidad").asInt()).isEqualTo(1);
        assertThat(vendedor.get("total").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(delDueno.get("cantidad").asInt()).isZero();
        assertThat(delDueno.get("total").decimalValue()).isEqualByComparingTo("-180.00");
        assertThat(vendedor.get("ganancia").decimalValue().add(delDueno.get("ganancia").decimalValue())).isZero();
    }

    @Test
    @DisplayName("por medio de pago no resta la NC (no mueve plata) y con descuento lista solo la venta")
    void medioDePagoYDescuentoNoLaCuentan() throws Exception {
        JsonNode pagos = reporte("por-medio-pago", ayer, hoy);
        JsonNode conDescuento = reporte("con-descuento", ayer, hoy);

        assertThat(pagos.get("filas")).hasSize(1);
        assertThat(pagos.get("filas").get(0).get("monto").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(conDescuento.get("filas")).hasSize(1);
        assertThat(conDescuento.get("filas").get(0).get("ventaId").asLong()).isEqualTo(venta);
    }

    @Test
    @DisplayName("por articulo y por rubro: la NC resta la cantidad, el total y la ganancia de sus renglones")
    void porArticuloYRubroRestan() throws Exception {
        JsonNode articulos = reporte("por-articulo", ayer, hoy);
        JsonNode rubros = reporte("por-rubro", ayer, hoy);

        JsonNode delTornillo = fila(articulos, "articuloId", String.valueOf(tornillo));
        assertThat(delTornillo.get("cantidad").decimalValue()).isZero();
        assertThat(delTornillo.get("total").decimalValue()).isZero();
        assertThat(delTornillo.get("ganancia").decimalValue()).isZero();
        assertThat(delTornillo.get("renglones").asInt()).isEqualTo(1);
        assertThat(delTornillo.get("renglonesSinCosto").asInt()).isZero();
        JsonNode deBulones = fila(rubros, "rubro", "Bulones");
        assertThat(deBulones.get("total").decimalValue()).isZero();
        assertThat(deBulones.get("ganancia").decimalValue()).isZero();
        JsonNode soloHoy = fila(reporte("por-articulo", hoy, hoy), "articuloId", String.valueOf(tornillo));
        assertThat(soloHoy.get("cantidad").decimalValue()).isEqualByComparingTo("-2");
        assertThat(soloHoy.get("total").decimalValue()).isEqualByComparingTo("-180.00");
    }

    @Test
    @DisplayName("Ventas lista la NC como renglon propio con la venta que anula, y la venta con quien, cuando y como se anulo")
    void ventasListaLaNotaYLaVentaAnulada() throws Exception {
        JsonNode pagina = getJson("/api/ventas", dueno);

        assertThat(pagina.get("totalElements").asLong()).isEqualTo(2);
        JsonNode laNota = pagina.get("items").get(0);
        assertThat(laNota.get("ventaId").asLong()).isEqualTo(nota);
        assertThat(laNota.get("tipo").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(laNota.get("numero").asLong()).isEqualTo(notaNumero);
        assertThat(laNota.get("total").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(laNota.get("usuario").asText()).isEqualTo("Dueno Ventas");
        assertThat(laNota.get("ventaAnulada").get("id").asLong()).isEqualTo(venta);
        assertThat(laNota.get("ventaAnulada").get("numero").asLong()).isEqualTo(ventaNumero);
        assertThat(laNota.get("anulacion").isNull()).isTrue();
        JsonNode laVenta = pagina.get("items").get(1);
        assertThat(laVenta.get("tipo").asText()).isEqualTo("VENTA");
        assertThat(laVenta.has("ventaAnulada")).isFalse();
        JsonNode anulacion = laVenta.get("anulacion");
        assertThat(anulacion.get("notaId").asLong()).isEqualTo(nota);
        assertThat(anulacion.get("numero").asLong()).isEqualTo(notaNumero);
        assertThat(anulacion.get("interna").asBoolean()).isFalse();
        assertThat(anulacion.get("motivo").isNull()).isTrue();
        assertThat(anulacion.get("usuario").asText()).isEqualTo("Dueno Ventas");
        assertThat(anulacion.get("fecha").asText()).startsWith(hoy.toString());
        // El filtro por numero tambien encuentra la NC.
        assertThat(getJson("/api/ventas?numero=" + notaNumero, dueno).get("items").get(0).get("ventaId").asLong())
                .isEqualTo(nota);
    }

    @Test
    @DisplayName("la anulacion interna se ve en la venta con su motivo, y la bandeja muestra una NC con su registro rechazado")
    void anulacionInternaYBandeja() throws Exception {
        long otraVenta = json(vender(dueno, clave(), "100.00", linea(tornillo, "1", "100.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        long laNota = nota;
        anular(otraVenta, "Se cobro dos veces");
        // Un registro fiscal rechazado de la primera NC, sembrado por JDBC: la bandeja es un filtro por ids de facturacion.
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, estado, resultado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, "
                + "emisor_cuit, emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, "
                + "emisor_ingresos_brutos, emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, "
                + "observaciones, errores, registrado_en) values (?, ?, 'SIMULADOR', 'FACTURA', 'B', 6, 1, 'RECHAZADO', 'R', "
                + "99, 0, 5, '30-78601360-3', 'Ferreteria', 'RESPONSABLE_INSCRIPTO', 'Calle 1', '901-1', '2015-03-01', "
                + "180.00, 148.76, 31.24, 0, 0, '[]', '[]', ?)", t.id(), laNota, LocalDateTime.now());

        JsonNode anulada = getJson("/api/ventas?numero=" + jdbc.queryForObject(
                "select numero from comprobante where id = ?", Long.class, otraVenta), dueno).get("items").get(0);
        JsonNode bandeja = getJson("/api/ventas?fiscal=RECHAZADAS", dueno);

        assertThat(anulada.get("anulacion").get("notaId").asLong()).isEqualTo(nota);
        assertThat(anulada.get("anulacion").get("interna").asBoolean()).isTrue();
        assertThat(anulada.get("anulacion").get("motivo").asText()).isEqualTo("Se cobro dos veces");
        assertThat(bandeja.get("items")).hasSize(1);
        assertThat(bandeja.get("items").get(0).get("ventaId").asLong()).isEqualTo(laNota);
        assertThat(bandeja.get("items").get(0).get("tipo").asText()).isEqualTo("NOTA_CREDITO");
    }

    @Test
    @DisplayName("el detalle de la NC se puede pedir, para reimprimirla")
    void detalleDeLaNota() throws Exception {
        JsonNode detalle = getJson("/api/ventas/" + nota, dueno);

        assertThat(detalle.get("ventaId").asLong()).isEqualTo(nota);
        assertThat(detalle.get("renglones")).hasSize(1);
    }

    @Test
    @DisplayName("Movimientos del articulo nombra la NC «Nota de credito N.º n» y la anulacion interna «Anulacion N.º n»; el "
            + "stock vuelve")
    void movimientosDelArticulo() throws Exception {
        long otraVenta = json(vender(dueno, clave(), "100.00", linea(tornillo, "1", "100.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        long laNota = notaNumero;
        anular(otraVenta, "Se cobro dos veces");

        JsonNode movimientos = getJson("/api/articulos/" + tornillo + "/movimientos-stock", dueno).get("items");

        assertThat(movimientos.findValuesAsText("tipo")).containsExactly("NOTA_CREDITO", "VENTA", "NOTA_CREDITO", "VENTA");
        assertThat(movimientos.get(0).get("comprobante").asText()).isEqualTo("Anulación N.º " + notaNumero);
        assertThat(movimientos.get(2).get("comprobante").asText()).isEqualTo("Nota de crédito N.º " + laNota);
        assertThat(movimientos.get(3).get("comprobante").asText()).isEqualTo("Venta N.º " + ventaNumero);
        assertThat(stock(tornillo)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("los clientes del articulo no cuentan la venta anulada")
    void clientesDelArticuloSinLaAnulada() throws Exception {
        JsonNode pagina = getJson("/api/articulos/" + tornillo + "/clientes", dueno);

        assertThat(pagina.get("totalElements").asLong()).isZero();
        assertThat(pagina.get("ventasSinCliente").asLong()).isZero();
    }

    @Test
    @DisplayName("otra ferreteria no ve ni suma la venta ni la NC")
    void otraFerreteriaNoVeNada() throws Exception {
        Tienda otra = tienda();
        String suDueno = login(otra.emailDueno());

        JsonNode resumen = getJson(REPORTES + "resumen?desde=" + ayer + "&hasta=" + hoy, suDueno);

        assertThat(resumen.get("cantidad").asInt()).isZero();
        assertThat(resumen.get("notasDeCredito").get("cantidad").asInt()).isZero();
        assertThat(resumen.get("total").decimalValue()).isZero();
        assertThat(getJson(REPORTES + "por-usuario?desde=" + ayer + "&hasta=" + hoy, suDueno).get("filas")).isEmpty();
        assertThat(getJson(REPORTES + "por-articulo?desde=" + ayer + "&hasta=" + hoy, suDueno).get("filas")).isEmpty();
        assertThat(getJson("/api/ventas", suDueno).get("totalElements").asLong()).isZero();
        getResult("/api/ventas/" + nota, suDueno).andExpect(status().isNotFound());
        getResult("/api/articulos/" + tornillo + "/movimientos-stock", suDueno).andExpect(status().isNotFound());
    }
}
