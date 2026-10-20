package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3B1-201 (CC-063, CC-064, CC-071, CC-102): la vigencia de las imputaciones contra MySQL real. Las tres consultas que
 * restan del pendiente ({@code imputadoPorComprobante}, {@code imputadoBloqueado}, {@code totalImputado}) ignoran las no
 * vigentes; {@code deRecibo} e {@code idsDeRecibo} las devuelven (reimpresión y repetida las necesitan); y la anulación
 * toma las filas por PK exacta y las da de baja con la ÚNICA mutación permitida. CUIT 30-8507NNNN.
 */
class ImputacionRepositoryVigenciaTest extends CuentaCorrienteTestBase {

    @Autowired
    ImputacionRepository imputaciones;

    @Autowired
    PlatformTransactionManager tm;

    @Override
    protected String prefijoDeCuit() {
        return "30-8507";
    }

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(tm).execute(status -> trabajo.get());
    }

    /** A (1.000) con un recibo vigente de 400 y otro, anulado, de 300; un recibo vigente de 100 a B (500). */
    private record Escenario(Ferreteria f, long cliente, VentaSuelta a, VentaSuelta b, long reciboVigente, long reciboAnulado,
            long reciboDeB) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        long vigente = cobroEnLibro(f, cliente, "400.00");
        long anulado = cobroEnLibro(f, cliente, "300.00");
        long deB = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, vigente, a.comprobanteId(), "400.00");
        imputarNoVigente(f, cliente, anulado, a.comprobanteId(), "300.00");
        imputar(f, cliente, deB, b.comprobanteId(), "100.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        return new Escenario(f, cliente, a, b, vigente, anulado, deB);
    }

    @Test
    @DisplayName("imputadoPorComprobante ignora las imputaciones no vigentes (A: 400 de la vigente, no 700)")
    void imputadoPorComprobanteIgnoraNoVigentes() {
        Escenario e = escenario();

        List<ImputadoDeComprobante> imputado = enTx(() -> imputaciones.imputadoPorComprobanteScoped(e.cliente()));

        assertThat(imputado).hasSize(2);
        assertThat(imputado).filteredOn(i -> i.getComprobanteId().equals(e.a().comprobanteId())).singleElement()
                .satisfies(i -> assertThat(i.getImputado()).isEqualByComparingTo("400.00"));
        assertThat(imputado).filteredOn(i -> i.getComprobanteId().equals(e.b().comprobanteId())).singleElement()
                .satisfies(i -> assertThat(i.getImputado()).isEqualByComparingTo("100.00"));
    }

    @Test
    @DisplayName("imputadoPorComprobante: un comprobante cuyas únicas imputaciones no son vigentes no figura como imputado")
    void imputadoPorComprobanteSoloNoVigentes() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        imputarNoVigente(f, cliente, cobroEnLibro(f, cliente, "300.00"), a.comprobanteId(), "300.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(enTx(() -> imputaciones.imputadoPorComprobanteScoped(cliente))).isEmpty();
    }

    @Test
    @DisplayName("imputadoBloqueado (FOR UPDATE) ignora las no vigentes: una sola fila de A, la de 400")
    void imputadoBloqueadoIgnoraNoVigentes() {
        Escenario e = escenario();

        List<FilaImputada> filas = enTx(() -> imputaciones.imputadoBloqueadoScoped(e.cliente(),
                List.of(e.a().comprobanteId(), e.b().comprobanteId())));

        assertThat(filas).hasSize(2);
        assertThat(filas).filteredOn(fila -> fila.getComprobanteId().equals(e.a().comprobanteId())).singleElement()
                .satisfies(fila -> assertThat(fila.getImporte()).isEqualByComparingTo("400.00"));
    }

    @Test
    @DisplayName("totalImputado ignora las no vigentes: 500 (400 + 100), no 800")
    void totalImputadoIgnoraNoVigentes() {
        Escenario e = escenario();

        assertThat(enTx(() -> imputaciones.totalImputadoScoped(e.cliente()))).isEqualByComparingTo("500.00");
    }

    /** Un recibo de 600 con tres imputaciones vigentes (a A, B y C), más un recibo ajeno del mismo cliente. */
    private record ReciboCon3(Ferreteria f, long cliente, long recibo, long otroRecibo, List<Long> ids, VentaSuelta a,
            VentaSuelta b, VentaSuelta c) {}

    private ReciboCon3 reciboConTresImputaciones() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta c = ventaEnLibro(f, cliente, "500.00");
        long recibo = cobroEnLibro(f, cliente, "600.00");
        long otro = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, recibo, c.comprobanteId(), "100.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "200.00");
        imputar(f, cliente, recibo, b.comprobanteId(), "300.00");
        imputar(f, cliente, otro, a.comprobanteId(), "100.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        List<Long> ids = List.of(imputacionId(recibo, c.comprobanteId()), imputacionId(recibo, a.comprobanteId()),
                imputacionId(recibo, b.comprobanteId()));
        return new ReciboCon3(f, cliente, recibo, otro, ids, a, b, c);
    }

    @Test
    @DisplayName("idsDeRecibo devuelve los ids del recibo ascendentes e incluye las no vigentes; vacío para un recibo sin imputaciones")
    void idsDeRecibo() {
        ReciboCon3 e = reciboConTresImputaciones();
        long noVigente = imputarNoVigente(e.f(), e.cliente(), e.otroRecibo(), e.b().comprobanteId(), "5.00");

        List<Long> delRecibo = enTx(() -> imputaciones.idsDeReciboScoped(e.recibo()));
        List<Long> delOtro = enTx(() -> imputaciones.idsDeReciboScoped(e.otroRecibo()));

        assertThat(delRecibo).containsExactlyElementsOf(e.ids().stream().sorted().toList());
        assertThat(delOtro).as("incluye la no vigente").hasSize(2).contains(noVigente).isSorted();
        assertThat(enTx(() -> imputaciones.idsDeReciboScoped(-1L))).isEmpty();
    }

    @Test
    @DisplayName("bloquearDeRecibo devuelve las filas con su vigencia en orden de id ascendente aunque se pidan descendentes")
    void bloquearDeReciboAscendente() {
        ReciboCon3 e = reciboConTresImputaciones();
        List<Long> descendentes = e.ids().stream().sorted(java.util.Comparator.reverseOrder()).toList();

        var filas = enTx(() -> imputaciones.bloquearDeReciboScoped(e.cliente(), descendentes));

        assertThat(filas).extracting(ImputacionRepository.FilaDeImputacion::getId)
                .containsExactlyElementsOf(e.ids().stream().sorted().toList());
        assertThat(filas).extracting(ImputacionRepository.FilaDeImputacion::getVigente).containsOnly(true);
        assertThat(filas).as("trae también el comprobante de cada fila (los «liberados» de la anulación)")
                .extracting(ImputacionRepository.FilaDeImputacion::getComprobanteId)
                .containsExactly(e.c().comprobanteId(), e.a().comprobanteId(), e.b().comprobanteId()); // se sembraron c, a, b
    }

    @Test
    @DisplayName("bloquearDeRecibo informa vigente=false de una fila ya desactivada (para que el servicio la rechace)")
    void bloquearDeReciboVigencia() {
        ReciboCon3 e = reciboConTresImputaciones();
        long noVigente = imputarNoVigente(e.f(), e.cliente(), e.otroRecibo(), e.b().comprobanteId(), "5.00");

        var filas = enTx(() -> imputaciones.bloquearDeReciboScoped(e.cliente(), List.of(e.ids().get(0), noVigente)));

        assertThat(filas).hasSize(2);
        assertThat(filas).filteredOn(f -> f.getId().equals(noVigente)).singleElement()
                .satisfies(f -> assertThat(f.getVigente()).isFalse());
        assertThat(filas).filteredOn(f -> f.getId().equals(e.ids().get(0))).singleElement()
                .satisfies(f -> assertThat(f.getVigente()).isTrue());
    }

    @Test
    @DisplayName("bloquearDeRecibo no cruza clientes ni ferreterías: ids ajenos no vuelven")
    void bloquearDeReciboScoping() {
        ReciboCon3 e = reciboConTresImputaciones();
        long otroCliente = cliente(e.f(), "5000.00");
        Ferreteria b = ferreteria();
        long clienteDeB = cliente(b, "5000.00");
        VentaSuelta ventaB = ventaEnLibro(b, clienteDeB, "100.00");
        imputar(b, clienteDeB, cobroEnLibro(b, clienteDeB, "100.00"), ventaB.comprobanteId(), "100.00");
        long idDeB = jdbc.queryForObject("select max(id) from imputacion where ferreteria_id = ?", Long.class, b.id());

        assertThat(enTx(() -> imputaciones.bloquearDeReciboScoped(otroCliente, e.ids()))).as("otro cliente").isEmpty();
        assertThat(enTx(() -> imputaciones.bloquearDeReciboScoped(e.cliente(), List.of(idDeB)))).as("otra ferretería")
                .isEmpty();
    }

    @Test
    @DisplayName("desactivarDeRecibo solo toca las imputaciones del recibo pedido, devuelve el conteo y es idempotente (la 2.ª vez 0 filas)")
    void desactivarDeRecibo() {
        ReciboCon3 e = reciboConTresImputaciones();

        int primera = enTx(() -> imputaciones.desactivarDeReciboScoped(e.cliente(), e.recibo(), e.ids()));
        int segunda = enTx(() -> imputaciones.desactivarDeReciboScoped(e.cliente(), e.recibo(), e.ids()));

        assertThat(primera).isEqualTo(3);
        assertThat(segunda).as("idempotente: ya no hay vigentes que bajar").isZero();
        assertThat(jdbc.queryForList("select vigente from imputacion where recibo_id = ?", Boolean.class, e.recibo()))
                .hasSize(3).containsOnly(false);
        assertThat(jdbc.queryForList("select vigente from imputacion where recibo_id = ?", Boolean.class, e.otroRecibo()))
                .as("el otro recibo del cliente queda vigente").hasSize(1).containsOnly(true);
        assertThat(enTx(() -> imputaciones.totalImputadoScoped(e.cliente()))).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("desactivarDeRecibo con ids de otro recibo, de otro cliente o de otra ferretería no toca nada (el recibo y los ids deben coincidir)")
    void desactivarDeReciboNoCruza() {
        ReciboCon3 e = reciboConTresImputaciones();
        long otroCliente = cliente(e.f(), "5000.00");
        Ferreteria b = ferreteria();

        int ajenoAlRecibo = enTx(() -> imputaciones.desactivarDeReciboScoped(e.cliente(), e.otroRecibo(), e.ids()));
        int otroClienteFilas = enTx(() -> imputaciones.desactivarDeReciboScoped(otroCliente, e.recibo(), e.ids()));
        TenantContext.setCurrentFerreteriaId(b.id());
        int otraFerreteria = enTx(() -> imputaciones.desactivarDeReciboScoped(e.cliente(), e.recibo(), e.ids()));
        TenantContext.setCurrentFerreteriaId(e.f().id());

        assertThat(ajenoAlRecibo).isZero();
        assertThat(otroClienteFilas).isZero();
        assertThat(otraFerreteria).isZero();
        assertThat(jdbc.queryForList("select vigente from imputacion where cliente_id = ?", Boolean.class, e.cliente()))
                .hasSize(4).containsOnly(true);
    }

    @Test
    @DisplayName("lectura ACTUAL: con la foto fijada por un SELECT plano, bloquearDeRecibo e imputadoBloqueado ven el vigente=0 que otra conexión confirmó después (la lectura plana no)")
    void lecturaActualVeLaDesactivacionConfirmada() {
        ReciboCon3 e = reciboConTresImputaciones();
        long id = e.ids().get(1); // la de A, 200

        Object[] vistas = enTx(() -> {
            long antes = jdbc.queryForObject("select count(*) from imputacion where cliente_id = ?", Long.class, e.cliente());
            com.ferreteria.support.BarreraDeLocks.holderJdbc("update imputacion set vigente = false where id = " + id)
                    .commit();
            List<Imputacion> plano = imputaciones.deReciboScoped(e.recibo());
            var bloqueadas = imputaciones.bloquearDeReciboScoped(e.cliente(), List.of(id));
            List<FilaImputada> imputado = imputaciones.imputadoBloqueadoScoped(e.cliente(), List.of(e.a().comprobanteId()));
            return new Object[] {antes, plano, bloqueadas, imputado};
        });

        assertThat(vistas[0]).isEqualTo(4L);
        @SuppressWarnings("unchecked")
        List<Imputacion> plano = (List<Imputacion>) vistas[1];
        assertThat(plano).as("la lectura plana ve la foto vieja: todas vigentes (la trampa que se evita)")
                .hasSize(3).allMatch(Imputacion::isVigente);
        @SuppressWarnings("unchecked")
        var bloqueadas = (List<ImputacionRepository.FilaDeImputacion>) vistas[2];
        assertThat(bloqueadas).singleElement().satisfies(f -> assertThat(f.getVigente())
                .as("el FOR UPDATE por PK lee lo último confirmado").isFalse());
        @SuppressWarnings("unchecked")
        List<FilaImputada> imputado = (List<FilaImputada>) vistas[3];
        assertThat(imputado).as("imputadoBloqueado ya no ve la fila desactivada (solo queda la del otro recibo)")
                .singleElement().satisfies(f -> assertThat(f.getImporte()).isEqualByComparingTo("100.00"));
    }

    @Test
    @DisplayName("sin gap locks: mientras se bloquea por PK y se da de baja, otra conexión inserta una imputación de otro cliente sin esperar (innodb_lock_wait_timeout=1)")
    void sinGapLocks() {
        ReciboCon3 e = reciboConTresImputaciones();
        long otroCliente = cliente(e.f(), "5000.00");
        VentaSuelta ventaOtro = ventaEnLibro(e.f(), otroCliente, "100.00");
        long reciboOtro = cobroEnLibro(e.f(), otroCliente, "100.00");

        enTx(() -> {
            imputaciones.bloquearDeReciboScoped(e.cliente(), e.ids());
            imputaciones.desactivarDeReciboScoped(e.cliente(), e.recibo(), e.ids()); // el UPDATE tampoco puede tomar gap locks
            com.ferreteria.support.BarreraDeLocks.holderJdbc("set session innodb_lock_wait_timeout = 1",
                    "insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) values ("
                            + e.f().id() + ", " + otroCliente + ", " + reciboOtro + ", " + ventaOtro.comprobanteId()
                            + ", 100.00)").commit();
            return null;
        });

        assertThat(imputacionId(reciboOtro, ventaOtro.comprobanteId())).isPositive();
    }

    @Test
    @DisplayName("deRecibo devuelve TAMBIÉN las no vigentes (reimpresión y cobranza repetida del recibo anulado)")
    void deReciboDevuelveLasNoVigentes() {
        Escenario e = escenario();

        List<Imputacion> delAnulado = enTx(() -> imputaciones.deReciboScoped(e.reciboAnulado()));

        assertThat(delAnulado).hasSize(1);
        assertThat(delAnulado.get(0).isVigente()).isFalse();
        assertThat(delAnulado.get(0).getImporte()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(enTx(() -> imputaciones.deReciboScoped(e.reciboVigente()))).singleElement()
                .satisfies(i -> assertThat(i.isVigente()).isTrue());
    }
}
