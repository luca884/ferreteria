package com.ferreteria.facturacion.wsfe;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El contrato del puerto {@link ServicioWsfe}: lo que cualquier implementación tiene que cumplir, la simulada y la real. Hoy
 * la corre {@code SimuladorWsfeContratoTest}. El punto de extensión de la rama 6 es una subclase más contra homologación,
 * marcada con un tag opt-in (por ejemplo {@code @Tag("homologacion")}, excluido por defecto en el surefire): si ARCA real se
 * aparta del contrato, la diferencia se agrega como test del simulador.
 *
 * <p>Los casos no suponen una serie vacía (homologación guarda lo autorizado): leen el último y piden el siguiente. Cada
 * código se busca donde lo pone el manual (ver {@link FeCaeResponse}): el rechazo de una validación del comprobante, en las
 * {@code Observaciones} del detalle con {@code Resultado} R; lo que no encuentra {@code FECompConsultar}, en {@code Errors}.
 */
public abstract class ContratoWsfeTest extends IntegrationTestBase {

    private static final int FACTURA_B = 6;

    /** La implementación bajo prueba. */
    protected abstract ServicioWsfe servicio();

    /** El ambiente y la CUIT emisora (Responsable Inscripto, que emite Factura B). */
    protected abstract Auth auth();

    /** Un punto de venta habilitado para WSFEv1 en ese ambiente. */
    protected abstract int puntoDeVenta();

    /** La fecha de envío (N) para la implementación: el día de hoy de ARCA. */
    protected abstract LocalDate fechaDeEnvio();

    /**
     * El comprobante quedó rechazado por una validación del detalle, como el ejemplo 4 del manual (p. 86): R en la cabecera y
     * en el detalle, sin CAE, y los códigos del rechazo en las {@code Observaciones} del detalle (devueltos acá).
     */
    public static Set<Integer> rechazadoPorElComprobante(FeCaeResponse respuesta) {
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp()).as("un rechazo del comprobante trae el detalle").isNotNull();
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().cae()).isNullOrEmpty();
        Set<Integer> codigos = new TreeSet<>();
        respuesta.feDetResp().observaciones().forEach(o -> codigos.add(o.code()));
        return codigos;
    }

    private long ultimo() {
        return servicio().feCompUltimoAutorizado(auth(), puntoDeVenta(), FACTURA_B).cbteNro();
    }

    private PedidoDePrueba facturaNumero(long numero) {
        return PedidoDePrueba.facturaB(fechaDeEnvio()).ptoVta(puntoDeVenta()).numero(numero);
    }

    @Test
    @DisplayName("contrato: el siguiente del último se autoriza con CAE, pasa a ser el último y se consulta con los mismos campos")
    void elSiguienteDelUltimoSeAutorizaYSeConsultaIgual() {
        long n = ultimo();
        PedidoDePrueba pedido = facturaNumero(n + 1);

        FeCaeResponse respuesta = servicio().feCaeSolicitar(auth(), pedido.armar());

        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.A);
        assertThat(respuesta.feDetResp().cbteDesde()).isEqualTo(n + 1);
        assertThat(respuesta.feDetResp().cae()).matches("\\d{14}");
        assertThat(respuesta.feDetResp().caeFchVto()).isAfterOrEqualTo(respuesta.feDetResp().cbteFch());
        assertThat(ultimo()).isEqualTo(n + 1);

        ResultGet consultado = servicio().feCompConsultar(auth(), FACTURA_B, puntoDeVenta(), n + 1).resultGet();
        assertThat(consultado).isNotNull();
        assertThat(consultado.detalle()).isEqualTo(pedido.detalle());
        assertThat(consultado.codAutorizacion()).isEqualTo(respuesta.feDetResp().cae());
        assertThat(consultado.fchVto()).isEqualTo(respuesta.feDetResp().caeFchVto());
        assertThat(consultado.resultado()).isEqualTo(Resultado.A);
    }

    @Test
    @DisplayName("contrato: un número que no es el siguiente se rechaza con 10016 en las Obs del detalle, sin CAE, y el último no cambia")
    void unNumeroQueNoEsElSiguienteSeRechaza() {
        long n = ultimo();

        FeCaeResponse respuesta = servicio().feCaeSolicitar(auth(), facturaNumero(n + 2).armar());

        assertThat(rechazadoPorElComprobante(respuesta)).contains(10016);
        assertThat(ultimo()).isEqualTo(n);
    }

    @Test
    @DisplayName("contrato: reenviar un número ya autorizado se rechaza con 10016 y el comprobante autorizado queda como estaba")
    void elMismoNumeroDosVecesSeRechaza() {
        long n = ultimo();
        FeCaeResponse primera = servicio().feCaeSolicitar(auth(), facturaNumero(n + 1).armar());
        assertThat(primera.feDetResp().resultado()).isEqualTo(Resultado.A);

        FeCaeResponse segunda = servicio().feCaeSolicitar(auth(), facturaNumero(n + 1).armar());

        assertThat(rechazadoPorElComprobante(segunda)).contains(10016);
        assertThat(ultimo()).isEqualTo(n + 1);
        assertThat(servicio().feCompConsultar(auth(), FACTURA_B, puntoDeVenta(), n + 1).resultGet().codAutorizacion())
                .isEqualTo(primera.feDetResp().cae());
    }

    @Test
    @DisplayName("contrato: sin CondicionIVAReceptorId se rechaza con 10246 (RG 5616) en las Obs del detalle")
    void sinCondicionIvaDelReceptorSeRechaza() {
        long n = ultimo();

        FeCaeResponse respuesta = servicio().feCaeSolicitar(auth(), facturaNumero(n + 1).condicion(null).armar());

        assertThat(rechazadoPorElComprobante(respuesta)).contains(10246);
        assertThat(ultimo()).isEqualTo(n);
    }

    @Test
    @DisplayName("contrato: consultar un número que no se autorizó no devuelve comprobante, y dice por qué en Errors")
    void unComprobanteQueNoExisteNoSeDevuelve() {
        long n = ultimo();

        FeCompConsultarResponse respuesta = servicio().feCompConsultar(auth(), FACTURA_B, puntoDeVenta(), n + 1000);

        assertThat(respuesta.resultGet()).isNull();
        assertThat(respuesta.errors()).isNotEmpty();
    }
}
