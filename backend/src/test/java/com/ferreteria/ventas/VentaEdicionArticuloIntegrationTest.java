package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-014 por HTTP (S-6 del verify): editar un articulo con {@code PUT /api/articulos/{id}} mientras se vende
 * con {@code POST /api/ventas} no pisa el stock ni muestra uno viejo.
 */
class VentaEdicionArticuloIntegrationTest extends VentasTestBase {

    private static final String MENSAJE_VERSION = "Otro cambio modificó este artículo. Recargá y volvé a intentar.";

    private String ediciones(long articuloId, String descripcion) throws Exception {
        String codigo = jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, articuloId);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("codigoInterno", codigo);
        cuerpo.put("descripcion", descripcion);
        cuerpo.put("costo", new BigDecimal("1.00"));
        cuerpo.put("alicuotaIva", "IVA_21");
        cuerpo.put("unidad", "UNIDAD");
        cuerpo.put("stockMinimo", BigDecimal.ZERO);
        // Como la pantalla: la version que se leyo (el PUT la exige).
        cuerpo.put("version", jdbc.queryForObject("select version from articulo where id = ?", Long.class, articuloId));
        return objectMapper.writeValueAsString(cuerpo);
    }

    private BigDecimal precioFinal(long articuloId) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, articuloId);
    }

    @Test
    void editarElArticuloEntreDosVentasDejaElStockCorrectoYLaRespuestaConElVigente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());

        vender(vendedor, clave(), "300.00", linea(a, "3", "100.00")).andExpect(status().isCreated());
        JsonNode editado = json(putJson("/api/articulos/" + a, dueno, ediciones(a, "Tornillo editado"))
                .andExpect(status().isOk()));
        BigDecimal precio = precioFinal(a);
        vender(vendedor, clave(), precio.multiply(new BigDecimal("2")).toPlainString(),
                linea(a, "2", precio.toPlainString())).andExpect(status().isCreated());

        assertThat(editado.get("descripcion").asText()).isEqualTo("Tornillo editado");
        assertThat(editado.get("stockActual").decimalValue()).isEqualByComparingTo("7");
        assertThat(stock(a)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, a))
                .isEqualTo("Tornillo editado");
        assertThat(jdbc.queryForObject("select coalesce(sum(cantidad), 0) from movimiento_stock where articulo_id = ?",
                BigDecimal.class, a)).isEqualByComparingTo("-5");
    }

    @Test
    void ventasYEdicionesAlMismoTiempoNoPierdenNiPisanStock() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        BigDecimal precio = precioDespuesDeUnaEdicion(a, dueno);
        int ventas = 6;
        int ediciones = 6;

        List<Callable<int[]>> trabajos = new ArrayList<>();
        for (int i = 0; i < ventas; i++) {
            trabajos.add(() -> {
                var respuesta = vender(vendedor, clave(), precio.toPlainString(), linea(a, "1", precio.toPlainString()))
                        .andReturn().getResponse();
                return new int[] {respuesta.getStatus(), 0};
            });
        }
        for (int i = 0; i < ediciones; i++) {
            String descripcion = "Tornillo v" + i;
            trabajos.add(() -> {
                var respuesta = putJson("/api/articulos/" + a, dueno, ediciones(a, descripcion)).andReturn().getResponse();
                // Una edicion que choca con otra del mismo articulo da 409 sin cuerpo de articulo (lote 5b, R-225).
                if (respuesta.getStatus() != 200) {
                    String mensaje = objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8))
                            .path("message").asText();
                    boolean esDeVersion = respuesta.getStatus() == 409 && MENSAJE_VERSION.equals(mensaje);
                    return new int[] {respuesta.getStatus(), esDeVersion ? -1 : -2};
                }
                BigDecimal visto = objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8))
                        .get("stockActual").decimalValue();
                return new int[] {respuesta.getStatus(), visto.intValue()};
            });
        }

        List<int[]> resultados = enParalelo(trabajos);

        assertThat(resultados.subList(0, ventas)).extracting(r -> r[0]).containsOnly(201);
        // Seis ediciones simultaneas del MISMO articulo: una gana y las que chocan dan 409 (nunca se pisan). Las ventas
        // no compiten con la version: todas dan 201 y el stock sale bien.
        List<int[]> deLasEdiciones = resultados.subList(ventas, ventas + ediciones);
        assertThat(deLasEdiciones).extracting(r -> r[0]).containsOnly(200, 409).contains(200);
        // Todo 409 es el de la version (-1), y los 200 mas los 409 son todas las ediciones.
        assertThat(deLasEdiciones).extracting(r -> r[1]).doesNotContain(-2);
        assertThat(deLasEdiciones.stream().filter(r -> r[0] == 200).count()
                + deLasEdiciones.stream().filter(r -> r[0] == 409).count()).isEqualTo(ediciones);
        // Cada edicion que se guardo ve un stock real (entre lo que quedaria si todas las ventas ya entraron y el inicial).
        assertThat(deLasEdiciones).filteredOn(r -> r[0] == 200).extracting(r -> r[1])
                .allSatisfy(visto -> assertThat(visto).isBetween(100 - ventas, 100));
        assertThat(stock(a)).isEqualByComparingTo(String.valueOf(100 - ventas));
        assertThat(jdbc.queryForObject("select coalesce(sum(cantidad), 0) from movimiento_stock where articulo_id = ?",
                BigDecimal.class, a)).isEqualByComparingTo(String.valueOf(-ventas));
    }

    /** Una edicion previa fija el precio final real (lo calcula el backend) para que las ventas no den 409. */
    private BigDecimal precioDespuesDeUnaEdicion(long a, String dueno) throws Exception {
        putJson("/api/articulos/" + a, dueno, ediciones(a, "Tornillo base")).andExpect(status().isOk());
        return precioFinal(a);
    }

    private <T> List<T> enParalelo(List<Callable<T>> trabajos) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        CountDownLatch listos = new CountDownLatch(trabajos.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    largada.await();
                    return trabajo.call();
                }));
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }
}
