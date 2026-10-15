package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.IntegrationTestBase;
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
import com.ferreteria.ventas.repository.PagoRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las entidades de ventas contra MySQL real: el arranque ya las valido con ddl-auto=validate; esto prueba
 * que el mapeo (enums como texto, importes, cantidades) hace el viaje de ida y vuelta y que la busqueda por
 * clave respeta la ferreteria.
 */
class ComprobanteRepositoryTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final String CLAVE = "5a1b0c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d";

    @Autowired
    ComprobanteRepository comprobanteRepository;

    @Autowired
    ItemComprobanteRepository itemRepository;

    @Autowired
    PagoRepository pagoRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("comprobante, item y pago hacen el viaje de ida y vuelta con sus tipos")
    void viajeDeIdaYVuelta() {
        Escenario e = escenario();
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());
        RenglonVenta renglon = RenglonVenta.de(new BigDecimal("333.33"), new BigDecimal("2.5"), new BigDecimal("21.00"));
        TotalesVenta totales = TotalesVenta.de(List.of(renglon));

        Long comprobanteId = new TransactionTemplate(transactionManager).execute(status -> {
            Comprobante guardado = comprobanteRepository.save(
                    Comprobante.venta(e.ferreteriaId(), 7L, CLAVE, e.usuarioId(), totales));
            itemRepository.saveAll(List.of(ItemComprobante.de(e.ferreteriaId(), guardado.getId(), 1, e.articuloId(),
                    "Cable unipolar", "METRO", "IVA_21", renglon)));
            pagoRepository.save(Pago.de(e.ferreteriaId(), guardado.getId(), MedioPago.DEBITO, totales.total()));
            return guardado.getId();
        });

        Comprobante leido = comprobanteRepository.findScoped(comprobanteId).orElseThrow();
        assertThat(leido.getTipo()).isEqualTo(TipoComprobante.VENTA);
        assertThat(leido.getEstado()).isEqualTo(EstadoComprobante.SIN_FACTURAR);
        assertThat(leido.getNumero()).isEqualTo(7L);
        assertThat(leido.getClienteId()).isNull();
        assertThat(leido.getTotal()).isEqualByComparingTo("833.33");
        assertThat(leido.getNeto()).isEqualByComparingTo("688.70");
        assertThat(leido.getIva()).isEqualByComparingTo("144.63");
        assertThat(leido.getFecha()).isNotNull();
        assertThat(jdbc.queryForObject("select tipo from comprobante where id = ?", String.class, comprobanteId))
                .isEqualTo("VENTA");
        assertThat(jdbc.queryForObject("select estado from comprobante where id = ?", String.class, comprobanteId))
                .isEqualTo("SIN_FACTURAR");
        assertThat(jdbc.queryForObject("select medio from pago where comprobante_id = ?", String.class, comprobanteId))
                .isEqualTo("DEBITO");
        assertThat(jdbc.queryForObject("select monto from pago where comprobante_id = ?", BigDecimal.class, comprobanteId))
                .isEqualByComparingTo("833.33");
        var item = jdbc.queryForMap("select * from item_comprobante where comprobante_id = ?", comprobanteId);
        assertThat(item.get("DESCRIPCION")).isEqualTo("Cable unipolar");
        assertThat(item.get("UNIDAD")).isEqualTo("METRO");
        assertThat(item.get("ALICUOTA_IVA")).isEqualTo("IVA_21");
        assertThat((BigDecimal) item.get("CANTIDAD")).isEqualByComparingTo("2.500");
        assertThat((BigDecimal) item.get("PRECIO_UNITARIO")).isEqualByComparingTo("333.33");
        assertThat((BigDecimal) item.get("TOTAL")).isEqualByComparingTo("833.33");
        assertThat(item.get("RENGLON")).isEqualTo(1);
    }

    @Test
    @DisplayName("porClaveScoped encuentra la venta de la ferreteria y no la de otra con la misma clave")
    void porClaveRespetaLaFerreteria() {
        Escenario a = escenario();
        Escenario b = escenario();
        TotalesVenta totales = TotalesVenta.de(List.of());
        new TransactionTemplate(transactionManager).execute(status ->
                comprobanteRepository.save(Comprobante.venta(a.ferreteriaId(), 1L, CLAVE, a.usuarioId(), totales)));

        TenantContext.setCurrentFerreteriaId(a.ferreteriaId());
        assertThat(comprobanteRepository.porClaveScoped(CLAVE)).isPresent();
        TenantContext.setCurrentFerreteriaId(b.ferreteriaId());
        assertThat(comprobanteRepository.porClaveScoped(CLAVE)).isEmpty();
    }

    private record Escenario(Long ferreteriaId, Long usuarioId, Long articuloId) {}

    private Escenario escenario() {
        String cuit = String.format("30-%08d-%d", 79700000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Comprobante " + cuit, cuit);
        Long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "comprobante-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno', 'DUENO')", ferreteriaId, email);
        Long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad) "
                + "values (?, 'CAB-1', 'Cable unipolar', 100.00, 333.33, 'METRO')", ferreteriaId);
        Long articuloId = jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ?", Long.class, ferreteriaId);
        return new Escenario(ferreteriaId, usuarioId, articuloId);
    }
}
