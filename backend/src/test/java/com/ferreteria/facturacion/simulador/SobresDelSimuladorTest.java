package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.arca.EsquemaWsfe;
import com.ferreteria.facturacion.arca.FeParamGetPtosVentaResponse;
import com.ferreteria.facturacion.arca.LectorWsfe;
import com.ferreteria.facturacion.arca.MetodoWsfe;
import com.ferreteria.facturacion.arca.PtoVenta;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las respuestas del simulador en el borde tienen la forma del WSDL (rama 6, S6): validan contra el XSD de WSFEv1 (C5) y el
 * mismo lector que lee las de ARCA ({@link LectorWsfe}) devuelve los mismos records que les dio el núcleo.
 */
class SobresDelSimuladorTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 28);
    private static final LocalDateTime PROCESO = LocalDateTime.of(2026, 10, 28, 10, 15, 30);
    private static final long CUIT = 30999999995L;

    @Test
    @DisplayName("FECAESolicitar aprobado (con observación) y rechazado en la cabecera: validan y se leen igual")
    void feCaeSolicitar() {
        FeCaeResponse aprobado = new FeCaeResponse(new FeCabResp(CUIT, 1, 6, PROCESO, 1, Resultado.A),
                new FeCaeDetResponse(1, 99, 0, 42, 42, HOY, Resultado.A, "76543210987654", HOY.plusDays(10),
                        List.of(new Obs(10217, "Observación & <prueba>"))), List.of());
        FeCaeResponse rechazado = new FeCaeResponse(new FeCabResp(CUIT, 1, 999, PROCESO, 1, Resultado.R), null,
                List.of(new Err(10007, "CbteTipo inválido")));

        for (FeCaeResponse respuesta : List.of(aprobado, rechazado)) {
            String sobre = SobresDelSimulador.feCaeSolicitar(respuesta);
            EsquemaWsfe.validarSobre(sobre);
            assertThat(LectorWsfe.feCaeSolicitar(sobre).respuesta()).isEqualTo(respuesta);
        }
    }

    @Test
    @DisplayName("FECompConsultar con el comprobante, y el 602 sin él: validan y se leen igual")
    void feCompConsultar() {
        ResultGet comprobante = new ResultGet(1, 6, PedidoDePrueba.facturaB(HOY).ptoVta(1).numero(41).armar().feDetReq(),
                Resultado.A, "76543210987650", "CAE", HOY.plusDays(10), PROCESO, List.of(new Obs(10217, "Obs")));
        FeCompConsultarResponse encontrado = new FeCompConsultarResponse(comprobante, List.of());
        FeCompConsultarResponse noEsta = new FeCompConsultarResponse(null, List.of(new Err(602, "Sin Resultados")));

        for (FeCompConsultarResponse respuesta : List.of(encontrado, noEsta)) {
            String sobre = SobresDelSimulador.feCompConsultar(respuesta);
            EsquemaWsfe.validarSobre(sobre);
            assertThat(LectorWsfe.feCompConsultar(sobre).respuesta()).isEqualTo(respuesta);
        }
    }

    @Test
    @DisplayName("FECompUltimoAutorizado, FEParamGetPtosVenta, FEDummy y el 600 de cada método con Auth: validan y se leen")
    void losDemas() {
        FeCompUltimoAutorizadoResponse ultimo = new FeCompUltimoAutorizadoResponse(1, 6, 41, List.of());
        String sobreUltimo = SobresDelSimulador.feCompUltimoAutorizado(ultimo);
        EsquemaWsfe.validarSobre(sobreUltimo);
        assertThat(LectorWsfe.feCompUltimoAutorizado(sobreUltimo).respuesta()).isEqualTo(ultimo);

        FeParamGetPtosVentaResponse puntos = new FeParamGetPtosVentaResponse(List.of(new PtoVenta(3, "CAE", false, null)),
                List.of());
        String sobrePuntos = SobresDelSimulador.feParamGetPtosVenta(puntos);
        EsquemaWsfe.validarSobre(sobrePuntos);
        assertThat(LectorWsfe.feParamGetPtosVenta(sobrePuntos).respuesta()).isEqualTo(puntos);

        String dummy = SobresDelSimulador.feDummy();
        EsquemaWsfe.validarSobre(dummy);
        assertThat(LectorWsfe.feDummy(dummy).appServer()).isEqualTo("OK");

        Err seiscientos = new Err(600, "ValidacionDeToken");
        assertThat(LectorWsfe.feCompUltimoAutorizado(validado(MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO, seiscientos))
                .respuesta().errors()).containsExactly(seiscientos);
        assertThat(LectorWsfe.feParamGetPtosVenta(validado(MetodoWsfe.FE_PARAM_GET_PTOS_VENTA, seiscientos))
                .respuesta().errors()).containsExactly(seiscientos);
        assertThat(LectorWsfe.feCaeSolicitar(validado(MetodoWsfe.FE_CAE_SOLICITAR, seiscientos)).respuesta().errors())
                .containsExactly(seiscientos);
        assertThat(LectorWsfe.feCompConsultar(validado(MetodoWsfe.FE_COMP_CONSULTAR, seiscientos)).respuesta().errors())
                .containsExactly(seiscientos);
    }

    private static String validado(MetodoWsfe metodo, Err error) {
        String sobre = SobresDelSimulador.rechazoDelAuth(metodo, error);
        EsquemaWsfe.validarSobre(sobre);
        return sobre;
    }
}
