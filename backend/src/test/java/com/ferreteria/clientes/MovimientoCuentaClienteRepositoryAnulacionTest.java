package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3B1-203 (CC-064, CC-071, CC-068): las consultas del libro que cambian con la anulación. El crédito sin imputar de un
 * cliente es Σ haber de los COBRO menos Σ debe de las ANULACION (un cobro anulado ya no es crédito), y el puerto de la
 * anulación mira los movimientos de UN recibo (COBRO y ANULACION) para defenderse. CUIT 30-8508NNNN.
 */
class MovimientoCuentaClienteRepositoryAnulacionTest extends CuentaCorrienteTestBase {

    @Autowired
    MovimientoCuentaClienteRepository libro;

    @Autowired
    PlatformTransactionManager tm;

    @Override
    protected String prefijoDeCuit() {
        return "30-8508";
    }

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(tm).execute(status -> trabajo.get());
    }

    @Test
    @DisplayName("creditoNetoDeRecibos = Σ haber COBRO − Σ debe ANULACION: un cobro de 700 anulado por 700 deja 0; el otro cobro sigue")
    void creditoNetoRestaLasAnulaciones() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        long anulado = cobroEnLibro(f, cliente, "700.00");
        anulacionEnLibro(f, cliente, anulado, "700.00");
        cobroEnLibro(f, cliente, "300.25");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(enTx(() -> libro.creditoNetoDeRecibosScoped(cliente))).isEqualByComparingTo("300.25");
    }

    @Test
    @DisplayName("creditoNetoDeRecibos: cobro anulado solo → 0; sin movimientos → 0; ignora las VENTA y el cliente de otra ferretería")
    void creditoNetoCeroIgnoraVentasYAjenos() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long soloAnulado = cliente(a, "5000.00");
        long sinNada = cliente(a, null);
        long conVentas = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        anulacionEnLibro(a, soloAnulado, cobroEnLibro(a, soloAnulado, "700.00"), "700.00");
        ventaEnLibro(a, conVentas, "999.00");
        cobroEnLibro(b, deB, "888.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThat(enTx(() -> libro.creditoNetoDeRecibosScoped(soloAnulado))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.creditoNetoDeRecibosScoped(sinNada))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.creditoNetoDeRecibosScoped(conVentas))).as("las VENTA no son crédito")
                .isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.creditoNetoDeRecibosScoped(deB))).as("cliente de otra ferretería")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("deReciboScoped devuelve el COBRO y la ANULACION del recibo y nada de otro recibo, cliente o ferretería")
    void deReciboDevuelveCobroYAnulacion() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        long recibo = cobroEnLibro(a, cliente, "700.00");
        long otroRecibo = cobroEnLibro(a, cliente, "50.00");
        long reciboDeB = cobroEnLibro(b, deB, "100.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        List<MovimientoCuentaCliente> soloCobro = enTx(() -> libro.deReciboScoped(recibo));
        anulacionEnLibro(a, cliente, recibo, "700.00");
        List<MovimientoCuentaCliente> conAnulacion = enTx(() -> libro.deReciboScoped(recibo));

        assertThat(soloCobro).extracting(MovimientoCuentaCliente::getTipo).containsExactly(TipoMovimientoCuenta.COBRO);
        assertThat(conAnulacion).extracting(MovimientoCuentaCliente::getTipo)
                .containsExactly(TipoMovimientoCuenta.COBRO, TipoMovimientoCuenta.ANULACION);
        assertThat(conAnulacion).extracting(MovimientoCuentaCliente::getReciboId).containsOnly(recibo);
        assertThat(conAnulacion.get(1).getDebe()).isEqualByComparingTo("700.00");
        assertThat(enTx(() -> libro.deReciboScoped(otroRecibo))).hasSize(1);
        assertThat(enTx(() -> libro.deReciboScoped(reciboDeB))).as("recibo de otra ferretería: 404 en la práctica").isEmpty();
        assertThat(enTx(() -> libro.deReciboScoped(-1L))).isEmpty();
    }
}
