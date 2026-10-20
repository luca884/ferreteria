package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.ReciboRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3-302 (CC-036, CC-038, CC-041, CC-053): el repositorio de recibos contra MySQL real: búsqueda por clave y por id con
 * aislamiento por ferretería, totales de caja por medio y los UNIQUE de número y clave. CUIT 30-8405NNNN.
 */
class ReciboRepositoryTest extends CuentaCorrienteTestBase {

    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 10, 19, 10, 0);

    @Autowired
    ReciboRepository recibos;

    @Override
    protected String prefijoDeCuit() {
        return "30-8405";
    }

    private Recibo guardar(Ferreteria f, long cliente, long caja, long numero, String clave, MedioPago medio, String total) {
        TenantContext.setCurrentFerreteriaId(f.id());
        return new TransactionTemplate(transactionManager).execute(status -> recibos.save(Recibo.emitir(f.id(), numero, clave,
                cliente, "Cliente " + cliente, caja, f.usuarioId(), FECHA, medio, new BigDecimal(total))));
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("porClaveScoped encuentra el recibo de la ferretería del request y no el de otra con la misma clave")
    void porClaveRespetaLaFerreteria() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        String clave = clave();
        Recibo deA = guardar(a, cliente(a, null), cajaCerrada(a), 1, clave, MedioPago.EFECTIVO, "100.00");
        Recibo deB = guardar(b, cliente(b, null), cajaCerrada(b), 1, clave, MedioPago.TRANSFERENCIA, "50.00");

        TenantContext.setCurrentFerreteriaId(a.id());
        assertThat(recibos.porClaveScoped(clave)).get().extracting(Recibo::getId).isEqualTo(deA.getId());
        TenantContext.setCurrentFerreteriaId(b.id());
        assertThat(recibos.porClaveScoped(clave)).get().extracting(Recibo::getId).isEqualTo(deB.getId());
        assertThat(recibos.porClaveScoped(clave()).isPresent()).isFalse();
    }

    @Test
    @DisplayName("el recibo de otra ferretería no se encuentra por id (404 en la API) y el propio sí, con todos sus campos")
    void porIdAjenoNoSeVe() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, null);
        Recibo deA = guardar(a, cliente, cajaCerrada(a), 7, clave(), MedioPago.TRANSFERENCIA, "1234.50");

        TenantContext.setCurrentFerreteriaId(b.id());
        assertThat(recibos.findScoped(deA.getId())).isEmpty();
        TenantContext.setCurrentFerreteriaId(a.id());
        Recibo leido = recibos.findScoped(deA.getId()).orElseThrow();
        assertThat(leido.getNumero()).isEqualTo(7L);
        assertThat(leido.getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(leido.getTotal()).isEqualByComparingTo("1234.50");
        assertThat(leido.getClienteId()).isEqualTo(cliente);
        assertThat(leido.getFecha()).isEqualTo(FECHA);
    }

    @Test
    @DisplayName("totalesDeCajaScoped suma por medio y solo de la caja pedida; sin recibos el mapa es vacío")
    void totalesPorMedioYPorCaja() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long cajaUno = cajaCerrada(f);
        long cajaDos = cajaCerrada(f);
        guardar(f, cliente, cajaUno, 1, clave(), MedioPago.EFECTIVO, "700.00");
        guardar(f, cliente, cajaUno, 2, clave(), MedioPago.EFECTIVO, "50.25");
        guardar(f, cliente, cajaUno, 3, clave(), MedioPago.TRANSFERENCIA, "300.00");
        guardar(f, cliente, cajaDos, 4, clave(), MedioPago.EFECTIVO, "999.00");

        TenantContext.setCurrentFerreteriaId(f.id());
        Map<MedioPago, BigDecimal> uno = recibos.totalesDeCajaScoped(cajaUno);
        assertThat(uno).containsOnlyKeys(MedioPago.EFECTIVO, MedioPago.TRANSFERENCIA);
        assertThat(uno.get(MedioPago.EFECTIVO)).isEqualByComparingTo("750.25");
        assertThat(uno.get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300.00");
        assertThat(recibos.totalesDeCajaScoped(cajaDos).get(MedioPago.EFECTIVO)).isEqualByComparingTo("999.00");
        assertThat(recibos.totalesDeCajaScoped(cajaCerrada(f))).isEmpty();
    }

    @Test
    @DisplayName("totalesDeCajaScoped no suma los recibos de otra ferretería aunque se pida el id de su caja")
    void totalesNoCruzanFerreterias() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cajaDeA = cajaCerrada(a);
        guardar(a, cliente(a, null), cajaDeA, 1, clave(), MedioPago.EFECTIVO, "100.00");

        TenantContext.setCurrentFerreteriaId(b.id());
        assertThat(recibos.totalesDeCajaScoped(cajaDeA)).isEmpty();
    }

    @Test
    @DisplayName("el UNIQUE (ferretería, número) y el de (ferretería, clave) rechazan repetidos; en otra ferretería entran")
    void uniquesDeNumeroYClave() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long clienteA = cliente(a, null);
        long cajaA = cajaCerrada(a);
        String clave = clave();
        guardar(a, clienteA, cajaA, 1, clave, MedioPago.EFECTIVO, "10.00");

        assertThatThrownBy(() -> guardar(a, clienteA, cajaA, 1, clave(), MedioPago.EFECTIVO, "10.00"))
                .as("mismo número").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> guardar(a, clienteA, cajaA, 2, clave, MedioPago.EFECTIVO, "10.00"))
                .as("misma clave").isInstanceOf(DataIntegrityViolationException.class);
        guardar(b, cliente(b, null), cajaCerrada(b), 1, clave, MedioPago.EFECTIVO, "10.00"); // otra ferretería: entra
        guardar(a, clienteA, cajaA, 2, clave(), MedioPago.EFECTIVO, "10.00");
    }
}
