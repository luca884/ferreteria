package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputada;
import com.ferreteria.clientes.repository.ImputacionRepository.ImputadoDeComprobante;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CC-026, CC-030, CC-041, CC-048: las consultas de imputaciones contra MySQL real. {@code imputadoBloqueado} es nativa, con
 * {@code FOR UPDATE} y filas crudas: una lectura ACTUAL, inmune a la foto de REPEATABLE READ, que es la única lectura de la
 * cobranza que no puede ser vieja (un imputado viejo SOBRE-estima el pendiente).
 */
class ImputacionRepositoryTest extends CuentaCorrienteTestBase {

    @Autowired
    ImputacionRepository imputaciones;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transactionManager).execute(status -> trabajo.get());
    }

    @Test
    @DisplayName("imputadoPorComprobanteScoped suma por comprobante las imputaciones del cliente (varios recibos al mismo comprobante)")
    void imputadoPorComprobante() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        long recibo1 = cobroEnLibro(f, cliente, "400.00");
        long recibo2 = cobroEnLibro(f, cliente, "350.50");
        imputar(f, cliente, recibo1, a.comprobanteId(), "300.00");
        imputar(f, cliente, recibo1, b.comprobanteId(), "100.00");
        imputar(f, cliente, recibo2, a.comprobanteId(), "200.50");
        TenantContext.setCurrentFerreteriaId(f.id());

        List<ImputadoDeComprobante> imputado = enTx(() -> imputaciones.imputadoPorComprobanteScoped(cliente));

        assertThat(imputado).hasSize(2);
        assertThat(imputado).filteredOn(i -> i.getComprobanteId().equals(a.comprobanteId())).singleElement()
                .satisfies(i -> assertThat(i.getImputado()).isEqualByComparingTo("500.50"));
        assertThat(imputado).filteredOn(i -> i.getComprobanteId().equals(b.comprobanteId())).singleElement()
                .satisfies(i -> assertThat(i.getImputado()).isEqualByComparingTo("100.00"));
    }

    @Test
    @DisplayName("el scoping: no se ven las imputaciones de otro cliente ni de otra ferretería, ni en el plano ni en el bloqueado ni en el total")
    void scoping() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long propio = cliente(a, "5000.00");
        long otro = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        VentaSuelta ventaPropia = ventaEnLibro(a, propio, "1000.00");
        VentaSuelta ventaOtro = ventaEnLibro(a, otro, "1000.00");
        VentaSuelta ventaDeB = ventaEnLibro(b, deB, "1000.00");
        imputar(a, propio, cobroEnLibro(a, propio, "100.00"), ventaPropia.comprobanteId(), "100.00");
        imputar(a, otro, cobroEnLibro(a, otro, "200.00"), ventaOtro.comprobanteId(), "200.00");
        imputar(b, deB, cobroEnLibro(b, deB, "300.00"), ventaDeB.comprobanteId(), "300.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThat(enTx(() -> imputaciones.imputadoPorComprobanteScoped(propio))).extracting(ImputadoDeComprobante::getComprobanteId)
                .containsExactly(ventaPropia.comprobanteId());
        assertThat(enTx(() -> imputaciones.imputadoPorComprobanteScoped(deB))).isEmpty();
        assertThat(enTx(() -> imputaciones.imputadoBloqueadoScoped(propio,
                List.of(ventaPropia.comprobanteId(), ventaOtro.comprobanteId(), ventaDeB.comprobanteId()))))
                .extracting(FilaImputada::getComprobanteId).containsExactly(ventaPropia.comprobanteId());
        assertThat(enTx(() -> imputaciones.totalImputadoScoped(propio))).isEqualByComparingTo("100.00");
        assertThat(enTx(() -> imputaciones.totalImputadoScoped(deB))).as("cliente de otra ferretería").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("imputadoBloqueadoScoped devuelve filas CRUDAS (dos imputaciones al mismo comprobante son dos filas) solo de los comprobantes pedidos")
    void imputadoBloqueadoFilasCrudas() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta noPedido = ventaEnLibro(f, cliente, "700.00");
        long recibo1 = cobroEnLibro(f, cliente, "300.00");
        long recibo2 = cobroEnLibro(f, cliente, "250.00");
        imputar(f, cliente, recibo1, a.comprobanteId(), "300.00");
        imputar(f, cliente, recibo2, a.comprobanteId(), "100.00");
        imputar(f, cliente, recibo2, noPedido.comprobanteId(), "150.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        List<FilaImputada> filas = enTx(() -> imputaciones.imputadoBloqueadoScoped(cliente,
                List.of(a.comprobanteId(), b.comprobanteId())));

        assertThat(filas).hasSize(2).extracting(FilaImputada::getComprobanteId).containsOnly(a.comprobanteId());
        assertThat(filas).extracting(FilaImputada::getImporte).usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("300.00"), new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("imputadoBloqueado es lectura ACTUAL: con la foto de la tx fijada por un SELECT plano, ve la imputación que otra conexión confirmó después; la lectura plana, no")
    void imputadoBloqueadoEsLecturaActual() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        long recibo = cobroEnLibro(f, cliente, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        var vistas = enTx(() -> {
            // La foto de esta tx (REPEATABLE READ) se fija con la primera lectura consistente: un SELECT plano.
            long antes = jdbc.queryForObject("select count(*) from imputacion where cliente_id = ?", Long.class, cliente);
            // Otra conexión (autocommit) confirma una imputación que agota el comprobante, DESPUÉS de la foto.
            imputarDesdeOtraConexion(f, cliente, recibo, a.comprobanteId());
            List<ImputadoDeComprobante> plano = imputaciones.imputadoPorComprobanteScoped(cliente);
            List<FilaImputada> actual = imputaciones.imputadoBloqueadoScoped(cliente, List.of(a.comprobanteId()));
            return new Object[] {antes, plano, actual};
        });

        assertThat(vistas[0]).isEqualTo(0L);
        assertThat((List<?>) vistas[1]).as("la lectura plana ve la foto vieja (la trampa que la cobranza evita)").isEmpty();
        @SuppressWarnings("unchecked")
        List<FilaImputada> actual = (List<FilaImputada>) vistas[2];
        assertThat(actual).as("el FOR UPDATE lee lo último confirmado").hasSize(1);
        assertThat(actual.get(0).getImporte()).isEqualByComparingTo("1000.00");
    }

    /** Otra conexión, fuera de la transacción del test: confirma al instante. */
    private void imputarDesdeOtraConexion(Ferreteria f, long cliente, long recibo, long comprobante) {
        // Conexión cruda aparte (no la del pool ligada a la tx del test), que confirma con su propio commit.
        com.ferreteria.support.BarreraDeLocks.HolderJdbc otra = com.ferreteria.support.BarreraDeLocks.holderJdbc(
                "insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) values ("
                        + f.id() + ", " + cliente + ", " + recibo + ", " + comprobante + ", 1000.00)");
        otra.commit();
    }

    @Test
    @DisplayName("deReciboScoped devuelve las imputaciones del recibo ordenadas por id ascendente")
    void deRecibo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "100.00");
        VentaSuelta c = ventaEnLibro(f, cliente, "100.00");
        long recibo = cobroEnLibro(f, cliente, "300.00");
        imputar(f, cliente, recibo, c.comprobanteId(), "30.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "10.00");
        imputar(f, cliente, recibo, b.comprobanteId(), "20.00");
        long otroRecibo = cobroEnLibro(f, cliente, "5.00");
        imputar(f, cliente, otroRecibo, a.comprobanteId(), "5.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        List<Imputacion> deRecibo = enTx(() -> imputaciones.deReciboScoped(recibo));

        assertThat(deRecibo).extracting(Imputacion::getComprobanteId)
                .containsExactly(c.comprobanteId(), a.comprobanteId(), b.comprobanteId());
        assertThat(deRecibo).extracting(Imputacion::getId).isSorted();
        assertThat(enTx(() -> imputaciones.deReciboScoped(-1L))).isEmpty();
    }

    @Test
    @DisplayName("totalImputadoScoped suma todas las imputaciones del cliente y da 0 si no hay")
    void totalImputado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        long sinNada = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "1000.00");
        long recibo = cobroEnLibro(f, cliente, "500.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "300.25");
        imputar(f, cliente, recibo, b.comprobanteId(), "199.75");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(enTx(() -> imputaciones.totalImputadoScoped(cliente))).isEqualByComparingTo("500.00");
        assertThat(enTx(() -> imputaciones.totalImputadoScoped(sinNada))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("guardar dos imputaciones del mismo recibo al mismo comprobante rompe el UNIQUE (recibo_id, comprobante_id)")
    void unicoPorReciboYComprobante() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        long recibo = cobroEnLibro(f, cliente, "500.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        enTx(() -> imputaciones.save(Imputacion.de(f.id(), cliente, recibo, a.comprobanteId(), new BigDecimal("100.00"))));

        assertThatThrownBy(() -> enTx(() -> imputaciones
                .saveAndFlush(Imputacion.de(f.id(), cliente, recibo, a.comprobanteId(), new BigDecimal("50.00")))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(enTx(() -> imputaciones.totalImputadoScoped(cliente))).isEqualByComparingTo("100.00");
    }
}
