package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static com.ferreteria.facturacion.wsfe.PedidoDePrueba.alicuota;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S4: las respuestas de WSFEv1 se leen hacia los records del puerto, con los {@code Errors}, las {@code Observaciones} y los
 * {@code Events} donde vienen, ignorando el header {@code FEHeaderInfo}. Una lista ausente es vacía, y un {@code CbteNro}
 * ausente vale 0. Los fixtures son los FEDummy reales (C7) y respuestas armadas con la forma del WSDL.
 */
class LectorWsfeTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"fedummy-homologacion", "fedummy-produccion"})
    @DisplayName("FEDummy real: los tres servidores OK, con el header FEHeaderInfo ignorado")
    void feDummyReal(String fixture) {
        FeDummyResponse dummy = LectorWsfe.feDummy(sobre(fixture));

        assertThat(dummy).isEqualTo(new FeDummyResponse("OK", "OK", "OK"));
        assertThat(dummy.todoOk()).isTrue();
    }

    @Test
    @DisplayName("FEParamGetPtosVenta: cada punto con su tipo, el bloqueado y el dado de baja, y los eventos")
    void puntosDeVenta() {
        ConEventos<FeParamGetPtosVentaResponse> leida = LectorWsfe.feParamGetPtosVenta(sobre("feparamgetptosventa"));

        assertThat(leida.respuesta().resultGet()).containsExactly(
                new PtoVenta(3, "CAE", false, null),
                new PtoVenta(4, "CAE", true, null),
                new PtoVenta(5, "CAEA", false, LocalDate.of(2025, 3, 1)));
        assertThat(leida.respuesta().errors()).isEmpty();
        assertThat(leida.eventos()).containsExactly(new Evt(1, "Evento de prueba, armado con la forma del WSDL."));
    }

    @Test
    @DisplayName("FEParamGetPtosVenta sin ResultGet: la lista vacía y el error")
    void puntosDeVentaConError() {
        ConEventos<FeParamGetPtosVentaResponse> leida =
                LectorWsfe.feParamGetPtosVenta(sobre("feparamgetptosventa-sin-puntos"));

        assertThat(leida.respuesta().resultGet()).isEmpty();
        assertThat(leida.respuesta().errors()).containsExactly(new Err(602, "Sin resultados (texto de prueba)."));
        assertThat(leida.eventos()).isEmpty();
    }

    @Test
    @DisplayName("FECompUltimoAutorizado: el último número; sin CbteNro (que el WSDL exige), 0")
    void ultimoAutorizado() {
        assertThat(LectorWsfe.feCompUltimoAutorizado(sobre("fecompultimoautorizado")).respuesta())
                .isEqualTo(new FeCompUltimoAutorizadoResponse(1, 6, 41, null));
        assertThat(LectorWsfe.feCompUltimoAutorizado(sobre("fecompultimoautorizado-sin-numero")).respuesta())
                .isEqualTo(new FeCompUltimoAutorizadoResponse(1, 6, 0, null));
    }

    @Test
    @DisplayName("FECAESolicitar aprobado: la cabecera, el detalle con el CAE y su vencimiento, la observación y el evento")
    void caeConSuVencimiento() {
        ConEventos<FeCaeResponse> leida = LectorWsfe.feCaeSolicitar(sobre("fecaesolicitar-aprobado"));

        assertThat(leida.respuesta()).isEqualTo(new FeCaeResponse(
                new FeCabResp(30999999995L, 1, 6, LocalDateTime.of(2026, 10, 29, 10, 30, 15), 1, Resultado.A),
                new FeCaeDetResponse(1, 99, 0, 42, 42, LocalDate.of(2026, 10, 29), Resultado.A, "76543210987654",
                        LocalDate.of(2026, 11, 8), java.util.List.of(new Obs(10217, "Observación de prueba, no excluyente."))),
                null));
        assertThat(leida.eventos()).containsExactly(new Evt(2, "Evento de prueba, armado con la forma del WSDL."));
    }

    @Test
    @DisplayName("FECAESolicitar rechazado: los errores y las observaciones juntos, sin CAE (los vacíos son null)")
    void erroresYObservacionesJuntos() {
        FeCaeResponse respuesta = LectorWsfe.feCaeSolicitar(sobre("fecaesolicitar-rechazado")).respuesta();

        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().cae()).isNull();
        assertThat(respuesta.feDetResp().caeFchVto()).isNull();
        assertThat(respuesta.feDetResp().observaciones()).containsExactly(
                new Obs(10016, "Primera observación de prueba."), new Obs(10048, "Segunda observación de prueba."));
        assertThat(respuesta.errors()).containsExactly(new Err(10000, "Error de prueba, junto con las observaciones."));
    }

    @Test
    @DisplayName("FECAESolicitar con el 600 y sin FeCabResp: el error, sin detalle, y la cabecera en R (no se procesó)")
    void el600() {
        FeCaeResponse respuesta = LectorWsfe.feCaeSolicitar(sobre("fecaesolicitar-600")).respuesta();

        assertThat(respuesta.errors()).containsExactly(new Err(600, "ValidacionDeToken: texto de prueba."));
        assertThat(respuesta.feDetResp()).isNull();
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
    }

    @Test
    @DisplayName("FECompConsultar: el comprobante entero, con el detalle igual al que se mandó (importes de .NET a dos "
            + "decimales), el CAE, su vencimiento, la hora de proceso, la observación y el evento")
    void comprobanteConsultado() {
        ConEventos<FeCompConsultarResponse> leida = LectorWsfe.feCompConsultar(sobre("fecompconsultar"));

        ResultGet resultGet = leida.respuesta().resultGet();
        assertThat(resultGet.detalle()).isEqualTo(PedidoDePrueba.facturaB(LocalDate.of(2026, 10, 28)).numero(41)
                .alicuotas(alicuota(5, "100.00", "21.00"), alicuota(4, "100.00", "10.50"))
                .importes("231.50", "200.00", "31.50").detalle());
        assertThat(resultGet.ptoVta()).isEqualTo(1);
        assertThat(resultGet.cbteTipo()).isEqualTo(6);
        assertThat(resultGet.resultado()).isEqualTo(Resultado.A);
        assertThat(resultGet.codAutorizacion()).isEqualTo("76543210987650");
        assertThat(resultGet.emisionTipo()).isEqualTo("CAE");
        assertThat(resultGet.fchVto()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(resultGet.fchProceso()).isEqualTo(LocalDateTime.of(2026, 10, 28, 18, 45));
        assertThat(resultGet.observaciones()).containsExactly(new Obs(10217, "Observación de prueba, no excluyente."));
        assertThat(leida.respuesta().errors()).isEmpty();
        assertThat(leida.eventos()).containsExactly(new Evt(3, "Evento de prueba, armado con la forma del WSDL."));
    }

    @Test
    @DisplayName("FECompConsultar de una nota de crédito: los comprobantes asociados y el período")
    void notaDeCreditoConsultada() {
        ResultGet resultGet = LectorWsfe.feCompConsultar(sobre("fecompconsultar-nota-de-credito")).respuesta().resultGet();

        assertThat(resultGet.cbteTipo()).isEqualTo(8);
        assertThat(resultGet.detalle().cbtesAsoc())
                .containsExactly(new CbteAsoc(6, 1, 41, "30999999995", LocalDate.of(2026, 10, 28)));
        assertThat(resultGet.detalle().periodoAsoc())
                .isEqualTo(new PeriodoAsoc(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28)));
        assertThat(resultGet.observaciones()).isEmpty();
    }

    @Test
    @DisplayName("FECompConsultar de un comprobante que no existe: sin ResultGet, con el 602")
    void comprobanteQueNoExiste() {
        FeCompConsultarResponse respuesta = LectorWsfe.feCompConsultar(sobre("fecompconsultar-602")).respuesta();

        assertThat(respuesta.resultGet()).isNull();
        assertThat(respuesta.errors()).containsExactly(new Err(602, "No existen datos en nuestros registros."));
    }

    @Test
    @DisplayName("lo que no es la respuesta esperada no se puede leer: HTML, un SOAP Fault o la respuesta de otro método")
    void ilegible() {
        String fault = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                + "<faultcode>soap:Server</faultcode><faultstring>Error</faultstring></soap:Fault></soap:Body>"
                + "</soap:Envelope>";

        assertThatThrownBy(() -> LectorWsfe.feDummy("<html><body>Service Unavailable</body></html>"))
                .isInstanceOf(XmlIlegibleException.class);
        assertThatThrownBy(() -> LectorWsfe.feCaeSolicitar(fault)).isInstanceOf(XmlIlegibleException.class)
                .hasMessageContaining("Fault");
        assertThatThrownBy(() -> LectorWsfe.feCaeSolicitar(sobre("fedummy-homologacion")))
                .isInstanceOf(XmlIlegibleException.class).hasMessageContaining("FECAESolicitarResponse");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"fedummy-homologacion", "fedummy-produccion", "feparamgetptosventa",
        "feparamgetptosventa-sin-puntos", "fecompultimoautorizado", "fecaesolicitar-aprobado", "fecaesolicitar-rechazado",
        "fecaesolicitar-600", "fecompconsultar", "fecompconsultar-nota-de-credito", "fecompconsultar-602"})
    @DisplayName("los fixtures armados tienen la forma del WSDL: validan contra el XSD")
    void losFixturesValidanContraElXsd(String fixture) {
        assertThat(EsquemaWsfe.validarSobre(sobre(fixture)).getNamespaceURI()).isEqualTo(MetodoWsfe.NAMESPACE);
    }
}
