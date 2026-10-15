package com.ferreteria.ventas;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-07 R-021: cobrar un ticket de 5 renglones tarda menos de 500 ms, tras 5 ventas de calentamiento (JIT,
 * pool de conexiones, planes). Se cronometran 20 ventas por el filtro de seguridad, el controlador y MySQL
 * reales, y se loguea la mediana y el maximo. Ferreteria propia: no toca los datos demo.
 */
class VentaRendimientoIntegrationTest extends VentasTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(VentaRendimientoIntegrationTest.class);

    private static final Duration LIMITE = Duration.ofMillis(500);
    private static final int CALENTAMIENTO = 5;
    private static final int VENTAS = 20;

    @Test
    void cobrarUnTicketDeCincoRenglonesTardaMenosDeMediaSegundo() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        long[] articulos = new long[5];
        for (int i = 0; i < articulos.length; i++) {
            articulos[i] = articulo(t, "Articulo " + i, "UNIDAD", "100.00", "10000");
        }
        Linea[] lineas = new Linea[articulos.length];
        for (int i = 0; i < articulos.length; i++) {
            lineas[i] = linea(articulos[i], "2", "100.00");
        }

        for (int i = 0; i < CALENTAMIENTO; i++) {
            vender(token, clave(), "1000.00", lineas).andExpect(status().isCreated());
        }

        List<Long> milisegundos = new ArrayList<>();
        for (int i = 0; i < VENTAS; i++) {
            String clave = clave();
            long inicio = System.nanoTime();
            vender(token, clave, "1000.00", lineas).andExpect(status().isCreated());
            Duration duracion = Duration.ofNanos(System.nanoTime() - inicio);
            milisegundos.add(duracion.toMillis());
            assertThat(duracion).as("venta %d", i + 1).isLessThan(LIMITE);
        }

        Collections.sort(milisegundos);
        LOG.info("RENDIMIENTO CU-07 (POST /api/ventas, 5 renglones): {} ventas, mediana {} ms, maximo {} ms (limite {} ms)",
                milisegundos.size(), milisegundos.get(milisegundos.size() / 2),
                milisegundos.get(milisegundos.size() - 1), LIMITE.toMillis());
        assertThat(comprobantes(t)).isEqualTo(CALENTAMIENTO + VENTAS);
    }
}
