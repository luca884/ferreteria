package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import com.ferreteria.tenant.TenantContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** S6: el simulador de WSFEv1 numera, autoriza y consulta como ARCA, con estado propio por ferretería. */
class SimuladorWsfeTest extends SimuladorWsfeTestBase {

    @Autowired
    SimuladorWsfe simuladorDelContexto;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Ambiente.class, names = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("rechaza todo ambiente que no sea SIMULADOR, en las tres operaciones, sin tocar su estado")
    void rechazaLosAmbientesReales(Ambiente ambiente) {
        Auth real = new Auth(ambiente, cuit);
        String mensaje = "El simulador de WSFEv1 solo atiende el ambiente SIMULADOR: un pedido de " + ambiente
                + " va a ARCA.";

        assertThatThrownBy(() -> simulador.feCompUltimoAutorizado(real, 1, 6))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(mensaje);
        assertThatThrownBy(() -> simulador.feCaeSolicitar(real, facturaB().armar()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(mensaje);
        assertThatThrownBy(() -> simulador.feCompConsultar(real, 6, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(mensaje);

        assertThat(ultimo(6)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from simulador_wsfe_serie where ferreteria_id = ?", Integer.class,
                ferreteriaId)).isZero();
    }

    @Test
    @DisplayName("una serie sin comprobantes da 0; el siguiente se autoriza con CAE de 14 dígitos, vence a los 10 días de la CbteFch y queda consultable")
    void numeraAutorizaYConsulta() {
        assertThat(simulador.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isZero();
        PedidoDePrueba pedido = facturaB().numero(1).cbteFch(HOY.minusDays(2));

        FeCaeResponse respuesta = solicitar(pedido);

        aprobado(respuesta);
        LocalDateTime proceso = LocalDateTime.of(2026, 10, 28, 10, 15, 30);
        assertThat(respuesta.feCabResp().cuit()).isEqualTo(cuit);
        assertThat(respuesta.feCabResp().ptoVta()).isEqualTo(1);
        assertThat(respuesta.feCabResp().cbteTipo()).isEqualTo(6);
        assertThat(respuesta.feCabResp().cantReg()).isEqualTo(1);
        assertThat(respuesta.feCabResp().fchProceso()).isEqualTo(proceso);
        assertThat(respuesta.feDetResp().cbteDesde()).isEqualTo(1);
        assertThat(respuesta.feDetResp().cbteHasta()).isEqualTo(1);
        assertThat(respuesta.feDetResp().docTipo()).isEqualTo(99);
        assertThat(respuesta.feDetResp().cbteFch()).isEqualTo(HOY.minusDays(2));
        assertThat(respuesta.feDetResp().caeFchVto()).isEqualTo(HOY.plusDays(8));
        assertThat(respuesta.feDetResp().observaciones()).isEmpty();

        assertThat(simulador.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isEqualTo(1);
        // Otras series no se mueven: otro tipo, otro punto de venta.
        assertThat(simulador.feCompUltimoAutorizado(auth, 1, 1).cbteNro()).isZero();
        assertThat(simulador.feCompUltimoAutorizado(auth, 2, 6).cbteNro()).isZero();

        FeCompConsultarResponse consulta = simulador.feCompConsultar(auth, 6, 1, 1);
        assertThat(consulta.errors()).isEmpty();
        ResultGet comprobante = consulta.resultGet();
        assertThat(comprobante.ptoVta()).isEqualTo(1);
        assertThat(comprobante.cbteTipo()).isEqualTo(6);
        assertThat(comprobante.detalle()).isEqualTo(pedido.detalle());
        assertThat(comprobante.resultado()).isEqualTo(Resultado.A);
        assertThat(comprobante.codAutorizacion()).isEqualTo(respuesta.feDetResp().cae());
        assertThat(comprobante.emisionTipo()).isEqualTo("CAE");
        assertThat(comprobante.fchVto()).isEqualTo(HOY.plusDays(8));
        assertThat(comprobante.fchProceso()).isEqualTo(proceso);
        assertThat(comprobante.observaciones()).isEmpty();

        // El siguiente es el 2, con otro CAE.
        FeCaeResponse segundo = solicitar(facturaB().numero(2));
        aprobado(segundo);
        assertThat(segundo.feDetResp().cae()).isNotEqualTo(respuesta.feDetResp().cae());
        assertThat(ultimo(6)).isEqualTo(2);
    }

    @Test
    @DisplayName("sin CbteFch, el comprobante toma la fecha de proceso (manual 4.8 p. 29)")
    void sinCbteFchTomaLaFechaDeProceso() {
        FeCaeResponse respuesta = solicitar(facturaB().cbteFch(null));

        aprobado(respuesta);
        assertThat(respuesta.feDetResp().cbteFch()).isEqualTo(HOY);
        assertThat(respuesta.feDetResp().caeFchVto()).isEqualTo(HOY.plusDays(10));
        assertThat(simulador.feCompConsultar(auth, 6, 1, 1).resultGet().detalle().cbteFch()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("consultar un número que no se autorizó: sin ResultGet y con 602 (inferido)")
    void consultarUnoQueNoExiste() {
        FeCompConsultarResponse respuesta = simulador.feCompConsultar(auth, 6, 1, 1);

        assertThat(respuesta.resultGet()).isNull();
        assertThat(respuesta.errors()).containsExactly(new Err(602, "No existen datos en nuestros registros."));
    }

    @Test
    @DisplayName("el estado sobrevive a un reinicio: una instancia nueva responde igual FECompUltimoAutorizado y FECompConsultar")
    void elEstadoSobreviveAUnReinicio() {
        solicitar(facturaB().numero(1));
        solicitar(facturaB().numero(2).documento(96, DNI).cbteFch(HOY.plusDays(1)));
        var ultimoAntes = simulador.feCompUltimoAutorizado(auth, 1, 6);
        var consultaAntes = simulador.feCompConsultar(auth, 6, 1, 2);

        SimuladorWsfe reiniciado = simulador(RELOJ, Duration.ZERO);

        assertThat(reiniciado.feCompUltimoAutorizado(auth, 1, 6)).isEqualTo(ultimoAntes);
        assertThat(reiniciado.feCompConsultar(auth, 6, 1, 2)).isEqualTo(consultaAntes);
        assertThat(ultimoAntes.cbteNro()).isEqualTo(2);
        assertThat(consultaAntes.resultGet().detalle().docNro()).isEqualTo(DNI);
        // Y sigue numerando desde ahí, con la CbteFch del último: el 3 no puede ser de antes.
        rechazadoCon(reiniciado.feCaeSolicitar(auth, facturaB().numero(3).armar()), 10016);
        aprobado(reiniciado.feCaeSolicitar(auth, facturaB().numero(3).cbteFch(HOY.plusDays(1)).armar()));
    }

    @Test
    @DisplayName("la latencia es configurable: cada operación tarda al menos eso; la del contexto de tests es 0")
    void laLatenciaEsConfigurable() {
        SimuladorWsfe lento = simulador(RELOJ, Duration.ofMillis(150));

        long inicio = System.nanoTime();
        lento.feCompUltimoAutorizado(auth, 1, 6);
        lento.feCaeSolicitar(auth, facturaB().armar());
        lento.feCompConsultar(auth, 6, 1, 1);
        Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(transcurrido).isGreaterThanOrEqualTo(Duration.ofMillis(450));
        assertThat(simuladorDelContexto.latencia()).isZero();
    }

    @Test
    @DisplayName("pedidos simultáneos con el mismo número: uno se autoriza y los demás dan 10016, sin excepciones")
    void pedidosSimultaneosEnLaMismaSerie() throws Exception {
        int hilos = 6;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<FeCaeResponse>> respuestas = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                respuestas.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(ferreteriaId);
                    try {
                        largada.await();
                        return simulador.feCaeSolicitar(auth, facturaB().numero(1).armar());
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            largada.countDown();
            List<FeCaeResponse> resultados = new ArrayList<>();
            for (Future<FeCaeResponse> respuesta : respuestas) {
                resultados.add(respuesta.get(30, TimeUnit.SECONDS));
            }

            assertThat(resultados).filteredOn(r -> r.feDetResp().resultado() == Resultado.A).hasSize(1);
            assertThat(resultados).filteredOn(r -> r.feDetResp().resultado() == Resultado.R).hasSize(hilos - 1)
                    .allSatisfy(r -> rechazadoCon(r, 10016));
            assertThat(ultimo(6)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("aislamiento: otra ferretería, aun con la misma CUIT, no ve la serie ni los comprobantes")
    void otraFerreteriaNoVeElEstado() {
        aprobado(solicitar(facturaB()));
        long otra = ferreteriaNuevaConCuit(cuitNuevo());

        TenantContext.setCurrentFerreteriaId(otra);

        assertThat(simulador.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isZero();
        assertThat(simulador.feCompConsultar(auth, 6, 1, 1).resultGet()).isNull();
        // Su serie arranca en 1, sin chocar con la de la primera.
        aprobado(solicitar(facturaB()));
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        assertThat(ultimo(6)).isEqualTo(1);
    }
}
