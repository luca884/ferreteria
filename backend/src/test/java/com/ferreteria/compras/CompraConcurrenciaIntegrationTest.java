package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.CompraRegistrada;
import com.ferreteria.compras.service.CompraService;
import com.ferreteria.compras.service.RegistrarCompra;
import com.ferreteria.compras.service.RegistrarCompra.RenglonCompra;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Dos compras a la vez (CU-13), contra MySQL real: la fachada resuelve la carrera de las claves unicas y reintenta el
 * bloqueo optimista del articulo. Cada caso corre 5 rondas de dos hilos que arrancan juntos (sondeo del verificador).
 */
class CompraConcurrenciaIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final LocalDate AYER = LocalDate.now().minusDays(1);
    private static final int RONDAS = 5;

    @Autowired
    CompraService compraService;

    Long ferreteria;
    Long usuario;
    Long proveedor;
    Long tornillo;

    @BeforeEach
    void datos() {
        String cuit = String.format("30-%08d-%d", 77900000 + SECUENCIA.incrementAndGet(), 4);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Concurrencia " + cuit, cuit);
        ferreteria = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "dueno" + SECUENCIA.incrementAndGet() + "@concurrencia.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno', 'DUENO')", ferreteria, email);
        usuario = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, 'Bulonera Sur', true)", ferreteria);
        proveedor = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                + "alicuota_iva, stock_actual, activo) values (?, 'TOR-1', 'Tornillo', 100.00, 157.30, 'UNIDAD', 'IVA_21', "
                + "10, true)", ferreteria);
        tornillo = jdbc.queryForObject("select id from articulo where ferreteria_id = ?", Long.class, ferreteria);
    }

    @Test
    @DisplayName("la misma clave a la vez: una es nueva, la otra es la repetida con el mismo id, y el stock suma una vez")
    void mismaClaveALaVez() throws Exception {
        for (int i = 0; i < RONDAS; i++) {
            RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1000L + i, "110.00");

            List<Object> resultados = aLaVez(pedido, pedido);

            assertThat(resultados).allMatch(r -> r instanceof CompraRegistrada);
            CompraRegistrada a = (CompraRegistrada) resultados.get(0);
            CompraRegistrada b = (CompraRegistrada) resultados.get(1);
            assertThat(a.compra().getId()).isEqualTo(b.compra().getId());
            assertThat(List.of(a.repetida(), b.repetida())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(stock()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("el mismo comprobante con otra clave a la vez: una se registra y la otra es 409, y el stock suma una vez")
    void mismoComprobanteALaVez() throws Exception {
        for (int i = 0; i < RONDAS; i++) {
            long numero = 2000L + i;

            List<Object> resultados = aLaVez(pedido(UUID.randomUUID().toString(), numero, "110.00"),
                    pedido(UUID.randomUUID().toString(), numero, "110.00"));

            assertThat(resultados).filteredOn(r -> r instanceof CompraRegistrada).hasSize(1);
            assertThat(resultados).filteredOn(r -> r instanceof ConflictException).singleElement()
                    .extracting(r -> ((ConflictException) r).getMessage())
                    .isEqualTo("Esa compra ya está cargada: Factura A 00003-%08d de Bulonera Sur.".formatted(numero));
        }
        assertThat(stock()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("dos compras distintas del mismo articulo a la vez se registran las dos y el stock suma ambas")
    void dosComprasDelMismoArticuloALaVez() throws Exception {
        for (int i = 0; i < RONDAS; i++) {
            List<Object> resultados = aLaVez(pedido(UUID.randomUUID().toString(), 3000L + 2 * i, "110.00"),
                    pedido(UUID.randomUUID().toString(), 3001L + 2 * i, "120.00"));

            assertThat(resultados).allMatch(r -> r instanceof CompraRegistrada);
        }
        assertThat(stock()).isEqualByComparingTo("210");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_stock where articulo_id = ? and tipo = 'COMPRA'",
                Integer.class, tornillo)).isEqualTo(2 * RONDAS);
    }

    /** Corre los dos pedidos en dos hilos que arrancan juntos; devuelve lo que devolvio cada uno o su excepcion. */
    private List<Object> aLaVez(RegistrarCompra a, RegistrarCompra b) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        CyclicBarrier largada = new CyclicBarrier(2);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (RegistrarCompra pedido : List.of(a, b)) {
                futuros.add(hilos.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(ferreteria);
                    try {
                        largada.await();
                        return compraService.registrar(pedido, usuario);
                    } catch (RuntimeException e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    private RegistrarCompra pedido(String clave, long numero, String costo) {
        return new RegistrarCompra(clave, proveedor, TipoCompra.FA, 3, numero, AYER,
                List.of(new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal(costo))));
    }

    private BigDecimal stock() {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, tornillo);
    }
}
