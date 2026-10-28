package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.ferreteria.facturacion.wsfe.WsfeConFallas;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Las fallas inyectables del puerto, solo en tests ({@link WsfeConFallas} sobre el simulador): qué excepción ve el que llama
 * y qué quedó autorizado del lado de ARCA.
 */
class WsfeConFallasTest extends SimuladorWsfeTestBase {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "NO_LLEGA, com.ferreteria.facturacion.wsfe.PedidoNoLlegoException, 0",
        "TIMEOUT_ANTES_DE_AUTORIZAR, com.ferreteria.facturacion.wsfe.ResultadoInciertoException, 0",
        "ERROR_502_ANTES_DE_AUTORIZAR, com.ferreteria.facturacion.wsfe.ResultadoInciertoException, 0",
        "TIMEOUT_DESPUES_DE_AUTORIZAR, com.ferreteria.facturacion.wsfe.ResultadoInciertoException, 1",
        "ERROR_502_DESPUES_DE_AUTORIZAR, com.ferreteria.facturacion.wsfe.ResultadoInciertoException, 1"})
    @DisplayName("cada falla tira su excepción y deja autorizado lo que corresponde; el pedido siguiente pasa al simulador")
    void cadaFalla(Falla falla, Class<? extends RuntimeException> excepcion, long autorizados) {
        WsfeConFallas wsfe = new WsfeConFallas(simulador).programar(falla);

        assertThatThrownBy(() -> wsfe.feCaeSolicitar(auth, facturaB().numero(1).armar())).isInstanceOf(excepcion);

        assertThat(wsfe.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isEqualTo(autorizados);
        assertThat(wsfe.feCompConsultar(auth, 6, 1, 1).resultGet() != null).isEqualTo(autorizados == 1);
        aprobado(wsfe.feCaeSolicitar(auth, facturaB().numero(autorizados + 1).armar()));
    }

    @Test
    @DisplayName("rechazo con observaciones: R sin CAE, con las observaciones en el detalle, y el número no se consume")
    void rechazoConObservaciones() {
        Obs obs = new Obs(10063, "El receptor no es un contribuyente activo.");
        WsfeConFallas wsfe = new WsfeConFallas(simulador).rechazarConObservaciones(obs);

        FeCaeResponse respuesta = wsfe.feCaeSolicitar(auth, facturaB().armar());

        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().cae()).isNull();
        assertThat(respuesta.feDetResp().observaciones()).containsExactly(obs);
        assertThat(respuesta.errors()).isEmpty();
        assertThat(wsfe.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isZero();
        aprobado(wsfe.feCaeSolicitar(auth, facturaB().armar()));
    }

    @Test
    @DisplayName("las fallas se consumen en orden, una por FECAESolicitar")
    void enOrden() {
        WsfeConFallas wsfe = new WsfeConFallas(simulador).programar(Falla.NO_LLEGA)
                .programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);

        assertThatThrownBy(() -> wsfe.feCaeSolicitar(auth, facturaB().armar())).isInstanceOf(PedidoNoLlegoException.class);
        assertThatThrownBy(() -> wsfe.feCaeSolicitar(auth, facturaB().armar()))
                .isInstanceOf(ResultadoInciertoException.class);
        rechazadoCon(wsfe.feCaeSolicitar(auth, facturaB().armar()), 10016);
        assertThat(wsfe.feCompUltimoAutorizado(auth, 1, 6).cbteNro()).isEqualTo(1);
    }
}
