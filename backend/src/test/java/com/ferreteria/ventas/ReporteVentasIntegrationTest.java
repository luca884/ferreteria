package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reportes de ventas (S2 a S11), como el menu Ventas de GDS: resumen, por usuario, por medio de pago, por dia y mes,
 * por articulo y rubro, y con descuento. Solo el Dueno. Los totales salen del total de cada comprobante (GDS-37).
 *
 * <p>El escenario de cada test: hoy el Vendedor vende 2 Tornillos (rubro Bulones, costo 40) en efectivo, el Dueno vende
 * 1 Arandela (sin rubro, costo 20) con 10 % de descuento en debito, y el Vendedor vende 1 Tornillo mas cuyo renglon
 * queda sin costo (una venta anterior a la V26).
 */
class ReporteVentasIntegrationTest extends VentasTestBase {

    private static final String BASE = "/api/reportes/ventas/";

    private Tienda t;
    private long tornillo;
    private long arandela;
    private long ventaVendedor;
    private long ventaDueno;
    private long ventaSinCosto;
    private String dueno;
    private LocalDate hoy;

    @BeforeEach
    void escenario() throws Exception {
        hoy = HoraArgentina.hoy();
        t = tienda();
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, 'Bulones', 30.00)", t.id());
        long bulones = jdbc.queryForObject("select id from rubro where ferreteria_id = ?", Long.class, t.id());
        tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "50");
        arandela = articulo(t, "Arandela", "UNIDAD", "50.00", "50");
        jdbc.update("update articulo set costo = 40.00, rubro_id = ? where id = ?", bulones, tornillo);
        jdbc.update("update articulo set costo = 20.00 where id = ?", arandela);
        dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());

        ventaVendedor = id(vender(vendedor, clave(), "200.00", linea(tornillo, "2", "100.00")));
        Map<String, Object> conDescuento = mapa(clave(), "DEBITO", "45.00", linea(arandela, "1", "50.00"));
        conDescuento.put("descuentoPorcentaje", new BigDecimal("10.00"));
        ventaDueno = id(postJson("/api/ventas", dueno, objectMapper.writeValueAsString(conDescuento)));
        ventaSinCosto = id(vender(vendedor, clave(), "100.00", linea(tornillo, "1", "100.00")));
        jdbc.update("update item_comprobante set costo_unitario = null, costo = null where comprobante_id = ?",
                ventaSinCosto);
    }

    private long id(org.springframework.test.web.servlet.ResultActions venta) throws Exception {
        return json(venta.andExpect(status().isCreated())).get("ventaId").asLong();
    }

    private BigDecimal columna(String columna, long comprobante) {
        return jdbc.queryForObject("select " + columna + " from comprobante where id = ?", BigDecimal.class, comprobante);
    }

    private JsonNode reporte(String nombre, String parametros) throws Exception {
        return getJson(BASE + nombre + "?" + parametros, dueno);
    }

    private String hoyAHoy() {
        return "desde=" + hoy + "&hasta=" + hoy;
    }

    private static BigDecimal bd(JsonNode nodo) {
        return nodo.decimalValue();
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
    @DisplayName("resumen: ventas, neto, IVA, costo y ganancia salen de los comprobantes; la venta sin costo se avisa")
    void resumen() throws Exception {
        JsonNode r = reporte("resumen", hoyAHoy());

        BigDecimal netoConCosto = columna("neto", ventaVendedor).add(columna("neto", ventaDueno));
        assertThat(r.get("desde").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("cantidad").asInt()).isEqualTo(3);
        assertThat(bd(r.get("total"))).isEqualByComparingTo("345.00");
        assertThat(bd(r.get("neto"))).isEqualByComparingTo(
                netoConCosto.add(columna("neto", ventaSinCosto)));
        assertThat(bd(r.get("iva"))).isEqualByComparingTo(columna("iva", ventaVendedor)
                .add(columna("iva", ventaDueno)).add(columna("iva", ventaSinCosto)));
        assertThat(bd(r.get("costo"))).isEqualByComparingTo("100.00");
        assertThat(bd(r.get("ganancia"))).isEqualByComparingTo(netoConCosto.subtract(new BigDecimal("100.00")));
        assertThat(r.get("ventasSinCosto").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("sin fechas el reporte es el de hoy")
    void sinFechasEsHoy() throws Exception {
        JsonNode r = getJson(BASE + "resumen", dueno);

        assertThat(r.get("desde").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("hasta").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("cantidad").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("el rango incluye el dia «hasta» completo y deja afuera lo de antes de «desde»")
    void rangoDeFechas() throws Exception {
        jdbc.update("update comprobante set fecha = ? where id = ?", hoy.minusDays(1).atTime(23, 59, 59), ventaDueno);

        assertThat(reporte("resumen", hoyAHoy()).get("cantidad").asInt()).isEqualTo(2);
        JsonNode ayer = reporte("resumen", "desde=" + hoy.minusDays(1) + "&hasta=" + hoy.minusDays(1));
        assertThat(ayer.get("cantidad").asInt()).isEqualTo(1);
        assertThat(bd(ayer.get("total"))).isEqualByComparingTo("45.00");
        assertThat(reporte("resumen", "desde=" + hoy.minusDays(1) + "&hasta=" + hoy).get("cantidad").asInt())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("por usuario: cantidad, total y ganancia de cada uno, con el nombre")
    void porUsuario() throws Exception {
        JsonNode r = reporte("por-usuario", hoyAHoy());

        assertThat(r.get("filas")).hasSize(2);
        JsonNode v = fila(r, "nombre", "Vendedor Ventas");
        assertThat(v.get("cantidad").asInt()).isEqualTo(2);
        assertThat(bd(v.get("total"))).isEqualByComparingTo("300.00");
        assertThat(bd(v.get("ganancia"))).isEqualByComparingTo(columna("neto", ventaVendedor).subtract(new BigDecimal("80")));
        assertThat(v.get("ventasSinCosto").asInt()).isEqualTo(1);
        JsonNode d = fila(r, "nombre", "Dueno Ventas");
        assertThat(d.get("cantidad").asInt()).isEqualTo(1);
        assertThat(bd(d.get("total"))).isEqualByComparingTo("45.00");
        assertThat(bd(d.get("ganancia"))).isEqualByComparingTo(columna("neto", ventaDueno).subtract(new BigDecimal("20")));
        assertThat(d.get("ventasSinCosto").asInt()).isZero();
        // Ordenado por total, de mayor a menor.
        assertThat(r.get("filas").get(0).get("nombre").asText()).isEqualTo("Vendedor Ventas");
    }

    @Test
    @DisplayName("por medio de pago: cantidad de pagos y monto por forma de pago; los montos suman el total")
    void porMedioDePago() throws Exception {
        JsonNode r = reporte("por-medio-pago", hoyAHoy());

        assertThat(r.get("filas")).hasSize(2);
        JsonNode efectivo = fila(r, "medio", "EFECTIVO");
        assertThat(efectivo.get("formaPago").asText()).isEqualTo("Efectivo");
        assertThat(efectivo.get("cantidad").asInt()).isEqualTo(2);
        assertThat(bd(efectivo.get("monto"))).isEqualByComparingTo("300.00");
        JsonNode debito = fila(r, "medio", "DEBITO");
        assertThat(debito.get("formaPago").asText()).isEqualTo("Débito");
        assertThat(debito.get("cantidad").asInt()).isEqualTo(1);
        assertThat(bd(debito.get("monto"))).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("por dia y por mes: un renglon por periodo con ventas, en orden")
    void porPeriodo() throws Exception {
        LocalDate antes = hoy.minusDays(40);
        jdbc.update("update comprobante set fecha = ? where id = ?", antes.atTime(10, 0), ventaDueno);
        String rango = "desde=" + antes + "&hasta=" + hoy;

        JsonNode dias = reporte("por-periodo", rango + "&agrupar=DIA");
        assertThat(dias.get("filas")).hasSize(2);
        assertThat(dias.get("filas").get(0).get("periodo").asText()).isEqualTo(antes.toString());
        assertThat(bd(dias.get("filas").get(0).get("total"))).isEqualByComparingTo("45.00");
        assertThat(dias.get("filas").get(1).get("periodo").asText()).isEqualTo(hoy.toString());
        assertThat(dias.get("filas").get(1).get("cantidad").asInt()).isEqualTo(2);
        assertThat(bd(dias.get("filas").get(1).get("total"))).isEqualByComparingTo("300.00");
        assertThat(dias.get("filas").get(1).get("ventasSinCosto").asInt()).isEqualTo(1);

        JsonNode meses = reporte("por-periodo", rango + "&agrupar=MES");
        assertThat(meses.get("filas")).hasSize(2);
        assertThat(meses.get("filas").get(0).get("periodo").asText()).isEqualTo(antes.toString().substring(0, 7));
        assertThat(meses.get("filas").get(1).get("periodo").asText()).isEqualTo(hoy.toString().substring(0, 7));
        assertThat(bd(meses.get("filas").get(1).get("ganancia"))).isEqualByComparingTo(
                columna("neto", ventaVendedor).subtract(new BigDecimal("80")));
    }

    @Test
    @DisplayName("por articulo: cantidad vendida, total y ganancia; el renglon sin costo se avisa")
    void porArticulo() throws Exception {
        JsonNode r = reporte("por-articulo", hoyAHoy());

        assertThat(r.get("filas")).hasSize(2);
        JsonNode tor = fila(r, "articuloId", String.valueOf(tornillo));
        assertThat(tor.get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(bd(tor.get("cantidad"))).isEqualByComparingTo("3");
        assertThat(bd(tor.get("total"))).isEqualByComparingTo("300.00");
        assertThat(bd(tor.get("ganancia"))).isEqualByComparingTo(columna("neto", ventaVendedor).subtract(new BigDecimal("80")));
        assertThat(tor.get("renglonesSinCosto").asInt()).isEqualTo(1);
        JsonNode ara = fila(r, "articuloId", String.valueOf(arandela));
        assertThat(bd(ara.get("cantidad"))).isEqualByComparingTo("1");
        assertThat(bd(ara.get("total"))).isEqualByComparingTo("45.00");
        assertThat(ara.get("renglonesSinCosto").asInt()).isZero();
        assertThat(r.get("filas").get(0).get("descripcion").asText()).isEqualTo("Tornillo");
    }

    @Test
    @DisplayName("por rubro: el rubro de hoy de cada articulo, y «Sin rubro» para los que no tienen")
    void porRubro() throws Exception {
        JsonNode r = reporte("por-rubro", hoyAHoy());

        assertThat(r.get("filas")).hasSize(2);
        JsonNode bulones = fila(r, "rubro", "Bulones");
        assertThat(bulones.get("renglones").asInt()).isEqualTo(2);
        assertThat(bd(bulones.get("total"))).isEqualByComparingTo("300.00");
        assertThat(bulones.get("renglonesSinCosto").asInt()).isEqualTo(1);
        JsonNode sinRubro = fila(r, "rubro", "Sin rubro");
        assertThat(sinRubro.get("renglones").asInt()).isEqualTo(1);
        assertThat(bd(sinRubro.get("total"))).isEqualByComparingTo("45.00");
        assertThat(bd(sinRubro.get("ganancia"))).isEqualByComparingTo(
                columna("neto", ventaDueno).subtract(new BigDecimal("20")));
    }

    @Test
    @DisplayName("con descuento: solo las ventas con descuento global o de un renglon, con el importe descontado")
    void conDescuento() throws Exception {
        JsonNode r = reporte("con-descuento", hoyAHoy());

        assertThat(r.get("filas")).hasSize(1);
        JsonNode v = r.get("filas").get(0);
        assertThat(v.get("ventaId").asLong()).isEqualTo(ventaDueno);
        assertThat(v.get("numero").asLong()).isPositive();
        assertThat(v.get("usuario").asText()).isEqualTo("Dueno Ventas");
        assertThat(bd(v.get("descuentoPorcentaje"))).isEqualByComparingTo("10.00");
        assertThat(bd(v.get("descuento"))).isEqualByComparingTo("5.00");
        assertThat(bd(v.get("total"))).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("con descuento: tambien entra la venta con descuento en un renglon")
    void conDescuentoDeRenglon() throws Exception {
        Map<String, Object> venta = mapa(clave(), "EFECTIVO", "90.00", linea(tornillo, "1", "100.00"));
        @SuppressWarnings("unchecked")
        Map<String, Object> renglon = ((java.util.List<Map<String, Object>>) venta.get("renglones")).get(0);
        renglon.put("descuentoPorcentaje", new BigDecimal("10.00"));
        long id = id(postJson("/api/ventas", dueno, objectMapper.writeValueAsString(venta)));

        JsonNode r = reporte("con-descuento", hoyAHoy());

        assertThat(r.get("filas")).hasSize(2);
        JsonNode v = fila(r, "ventaId", String.valueOf(id));
        assertThat(bd(v.get("descuentoPorcentaje"))).isEqualByComparingTo("0");
        assertThat(bd(v.get("descuento"))).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("presupuestos y remitos no son ventas: no entran en los reportes")
    void presupuestosYRemitosNoCuentan() throws Exception {
        presupuesto(dueno, tornillo, "1", "100.00", "100.00");
        remito(dueno, tornillo, "1", "100.00", "100.00");

        JsonNode r = reporte("resumen", hoyAHoy());
        assertThat(r.get("cantidad").asInt()).isEqualTo(3);
        assertThat(bd(r.get("total"))).isEqualByComparingTo("345.00");
    }

    @Test
    @DisplayName("la venta «No facturable» entra en los reportes")
    void noFacturableEntra() throws Exception {
        Map<String, Object> venta = mapa(clave(), "EFECTIVO", "100.00", linea(tornillo, "1", "100.00"));
        venta.put("facturable", false);
        id(postJson("/api/ventas", dueno, objectMapper.writeValueAsString(venta)));

        assertThat(reporte("resumen", hoyAHoy()).get("cantidad").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("otra ferreteria no ve estas ventas ni las suma (regla 7)")
    void aislamientoPorFerreteria() throws Exception {
        Tienda otra = tienda();
        long clavo = articulo(otra, "Clavo", "UNIDAD", "10.00", "50");
        vender(login(otra.emailVendedor()), clave(), "10.00", linea(clavo, "1", "10.00"))
                .andExpect(status().isCreated());
        String duenoOtra = login(otra.emailDueno());

        JsonNode suyo = getJson(BASE + "resumen?" + hoyAHoy(), duenoOtra);
        assertThat(suyo.get("cantidad").asInt()).isEqualTo(1);
        assertThat(bd(suyo.get("total"))).isEqualByComparingTo("10.00");
        assertThat(getJson(BASE + "por-articulo?" + hoyAHoy(), duenoOtra).get("filas")).hasSize(1);
        assertThat(getJson(BASE + "por-usuario?" + hoyAHoy(), duenoOtra).get("filas")).hasSize(1);
        assertThat(getJson(BASE + "por-medio-pago?" + hoyAHoy(), duenoOtra).get("filas")).hasSize(1);
        assertThat(getJson(BASE + "por-rubro?" + hoyAHoy(), duenoOtra).get("filas")).hasSize(1);
        assertThat(getJson(BASE + "con-descuento?" + hoyAHoy(), duenoOtra).get("filas")).isEmpty();
        assertThat(reporte("resumen", hoyAHoy()).get("cantidad").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("solo el Dueño: el Vendedor recibe 403 en los seis reportes")
    void soloElDueno() throws Exception {
        String vendedor = login(t.emailVendedor());
        for (String nombre : new String[] {"resumen", "por-usuario", "por-medio-pago", "por-periodo", "por-articulo",
                "por-rubro", "con-descuento"}) {
            getResult(BASE + nombre + "?" + hoyAHoy(), vendedor).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("fechas invalidas: «hasta» antes de «desde», mas de 366 dias o un agrupar desconocido dan 400")
    void fechasInvalidas() throws Exception {
        getResult(BASE + "resumen?desde=" + hoy + "&hasta=" + hoy.minusDays(1), dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.hasta").value("La fecha «Hasta» no puede ser anterior a «Desde»."));
        getResult(BASE + "resumen?desde=" + hoy.minusDays(366) + "&hasta=" + hoy, dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.desde").value("El reporte admite hasta 366 días."));
        getResult(BASE + "resumen?desde=" + hoy.minusDays(365) + "&hasta=" + hoy, dueno).andExpect(status().isOk());
        getResult(BASE + "resumen?desde=ayer", dueno).andExpect(status().isBadRequest());
        getResult(BASE + "por-periodo?agrupar=ANIO", dueno).andExpect(status().isBadRequest());
    }
}
