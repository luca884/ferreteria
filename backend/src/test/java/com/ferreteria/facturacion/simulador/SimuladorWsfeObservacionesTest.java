package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S6: las validaciones no excluyentes del manual 4.8 que copia el simulador. Aprueban (A, con CAE) y devuelven su código en
 * las {@code Observaciones} del detalle, que también quedan en lo que devuelve {@code FECompConsultar}. El simulador no tiene
 * los padrones de ARCA: lo que las dispara es inferido y está escrito en cada caso.
 */
class SimuladorWsfeObservacionesTest extends SimuladorWsfeTestBase {

    private static Obs obs(int codigo) {
        return new Obs(codigo, CodigoManual.de(codigo).texto());
    }

    /** Aprobado con exactamente esas observaciones, también al consultarlo. */
    private void aprobadoConObservaciones(FeCaeResponse respuesta, int cbteTipo, Obs... observaciones) {
        aprobado(respuesta);
        assertThat(respuesta.feDetResp().observaciones()).containsExactly(observaciones);
        assertThat(simulador.feCompConsultar(auth, cbteTipo, 1, respuesta.feDetResp().cbteDesde()).resultGet().observaciones())
                .containsExactly(observaciones);
    }

    @Test
    @DisplayName("10041: NC asociada a un comprobante que el simulador no autorizó (todo punto de venta cuenta como electrónico)")
    void asociadoQueNoExiste() {
        FeCaeResponse respuesta = solicitar(facturaB().tipo(8).asociados(new CbteAsoc(6, 1, 7, null, null)));

        aprobadoConObservaciones(respuesta, 8, obs(10041));
    }

    @Test
    @DisplayName("10063: Factura A a una CUIT fuera del padrón (dígito verificador inválido, inferido)")
    void facturaAFueraDelPadron() {
        FeCaeResponse respuesta = solicitar(facturaB().tipo(1).documento(80, CUIT_FUERA_DEL_PADRON).condicion(1));

        aprobadoConObservaciones(respuesta, 1, obs(10063));
    }

    @Test
    @DisplayName("10188: Factura con CUIT desde el mínimo de la FCE (inferido); un centavo menos, sin observación")
    void correspondiaFacturaDeCreditoElectronica() {
        PedidoDePrueba enElMinimo = facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(1).desglose(desglose(minimoFce));
        PedidoDePrueba unCentavoMenos = facturaB().tipo(1).numero(2).documento(80, CUIT_RECEPTOR).condicion(1)
                .desglose(desglose(minimoFce.subtract(new BigDecimal("0.01"))));

        aprobadoConObservaciones(solicitar(enElMinimo), 1, obs(10188));
        aprobadoConObservaciones(solicitar(unCentavoMenos), 1);
    }

    @Test
    @DisplayName("10217: Factura A a un Monotributista (CondicionIVAReceptorId 6, inferido)")
    void facturaAUnMonotributista() {
        FeCaeResponse respuesta = solicitar(facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(6));

        aprobadoConObservaciones(respuesta, 1, obs(10217));
    }

    @Test
    @DisplayName("10237: NC por más que el comprobante asociado; por lo mismo, sin observación")
    void notaDeCreditoQueSuperaAlAsociado() {
        aprobado(solicitar(facturaB()));
        CbteAsoc factura = new CbteAsoc(6, 1, 1, null, null);

        FeCaeResponse demas = solicitar(facturaB().tipo(8).asociados(factura).importes("121.02", "100.02", "21.00")
                .alicuotas(PedidoDePrueba.alicuota(5, "100.02", "21.00")));
        FeCaeResponse igual = solicitar(facturaB().tipo(8).numero(2).asociados(factura));

        aprobadoConObservaciones(demas, 8, obs(10237));
        aprobadoConObservaciones(igual, 8);
    }

    @Test
    @DisplayName("varias a la vez, en orden de código: Factura A a un Monotributista fuera del padrón")
    void variasObservaciones() {
        FeCaeResponse respuesta = solicitar(facturaB().tipo(1).documento(80, CUIT_FUERA_DEL_PADRON).condicion(6));

        aprobadoConObservaciones(respuesta, 1, obs(10063), obs(10217));
    }

    private static DesgloseFiscal desglose(BigDecimal total) {
        return DesgloseFiscal.de(Letra.A, List.of(new RenglonFiscal(Alicuota.IVA_21, total)));
    }
}
