package com.ferreteria.facturacion.simulador;

import static com.ferreteria.facturacion.wsfe.PedidoDePrueba.alicuota;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S6: las validaciones excluyentes del manual 4.8 que copia el simulador. Las del comprobante rechazan (R, sin CAE) con su
 * código en las {@code Observaciones} del detalle, como el ejemplo 4 del manual (p. 86); la de la cabecera (10007), en
 * {@code Errors} y sin detalle, como el ejemplo 3 (p. 84). Ninguna consume el número: el mismo número, bien armado, se
 * autoriza después. Las tolerancias son las del anexo (p. 202): relativo hasta 0,01 % o absoluto hasta 0,01 (por la
 * cantidad de alícuotas en 10023 y 10061).
 */
class SimuladorWsfeValidacionesTest extends SimuladorWsfeTestBase {

    private static final PeriodoAsoc OCTUBRE = new PeriodoAsoc(HOY.withDayOfMonth(1), HOY.withDayOfMonth(31));

    /** Los códigos de las validaciones no excluyentes (aprueban con CAE). */
    private static final List<Integer> OBSERVACIONES = List.of(10041, 10063, 10188, 10217, 10237);

    private static Arguments caso(String nombre, UnaryOperator<PedidoDePrueba> cambio, int codigo) {
        return arguments(nombre, cambio, codigo);
    }

    /** Factura C de $ 121: ImpNeto es el total, sin IVA ni alícuotas. */
    private static PedidoDePrueba facturaC(PedidoDePrueba pedido) {
        return pedido.tipo(11).importes("121.00", "121.00", "0.00").alicuotas();
    }

    static Stream<Arguments> rechazos() {
        return Stream.of(
                caso("10013: Factura A a un DNI", p -> p.tipo(1).documento(96, DNI).condicion(1), 10013),
                caso("10015: Factura B a DocTipo 99 con DocNro", p -> p.documento(99, DNI), 10015),
                caso("10015: Factura B a una CUIT fuera del padrón", p -> p.documento(80, CUIT_FUERA_DEL_PADRON).condicion(4),
                        10015),
                caso("10015: Factura B a un DNI sin número", p -> p.documento(96, 0), 10015),
                caso("10015: Factura B con un DocTipo que no existe", p -> p.documento(7, DNI), 10015),
                caso("10016: número salteado", p -> p.numero(2), 10016),
                caso("10016: CbteFch N-6 con concepto 1", p -> p.cbteFch(HOY.minusDays(6)), 10016),
                caso("10016: CbteFch N+6 con concepto 1", p -> p.cbteFch(HOY.plusDays(6)), 10016),
                caso("10016: CbteFch N-11 con concepto 2", p -> p.concepto(2).cbteFch(HOY.minusDays(11)), 10016),
                caso("10152: CbteFch N+4 con concepto 1, en el mes siguiente", p -> p.cbteFch(HOY.plusDays(4)), 10152),
                caso("10023: la suma de Importe difiere de ImpIVA en 0,05", p -> p.importes("121.05", "100.00", "21.05"),
                        10023),
                caso("10023: con dos alícuotas, difiere en 0,03 (tolera 0,02)", p -> p.importes("231.53", "200.00", "31.53")
                        .alicuotas(alicuota(4, "100.00", "10.50"), alicuota(5, "100.00", "21.00")), 10023),
                caso("10040: Factura C con CbtesAsoc", p -> facturaC(p).asociados(new CbteAsoc(11, 1, 1, null, null)), 10040),
                caso("10040: NC B asociada a una Factura A", p -> p.tipo(8).asociados(new CbteAsoc(1, 1, 1, null, null)),
                        10040),
                caso("10048: ImpTotal 0,05 más que la suma", p -> p.impTotal("121.05"), 10048),
                caso("10048: en la C, ImpTotal distinto de ImpNeto + ImpTrib", p -> facturaC(p).impTotal("121.05"), 10048),
                caso("10048: 0,0165 % sobre un total grande (tolera 0,01 %)", p -> p.documento(96, DNI)
                        .importes("1210200.00", "1000000.00", "210000.00").alicuotas(alicuota(5, "1000000.00", "210000.00")),
                        10048),
                caso("10051: Importe que no es el 21 % de BaseImp (0,02 de más)", p -> p.importes("121.02", "100.00", "21.02")
                        .alicuotas(alicuota(5, "100.00", "21.02")), 10051),
                caso("10051: Id de alícuota que no existe (inferido)", p -> p.alicuotas(alicuota(7, "100.00", "21.00")), 10051),
                caso("10061: la suma de BaseImp difiere de ImpNeto en 0,05", p -> p.importes("121.05", "100.05", "21.00"),
                        10061),
                caso("10061: con dos alícuotas, difiere en 0,03 (tolera 0,02)", p -> p.importes("231.53", "200.03", "31.50")
                        .alicuotas(alicuota(4, "100.00", "10.50"), alicuota(5, "100.00", "21.00")), 10061),
                caso("10071: Factura C con array de IVA", p -> facturaC(p).alicuotas(alicuota(3, "121.00", "0.00")), 10071),
                caso("10197: NC B sin CbtesAsoc ni PeriodoAsoc", p -> p.tipo(8), 10197),
                caso("10242: CondicionIVAReceptorId que no existe", p -> p.condicion(2), 10242));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rechazos")
    @DisplayName("cada validación excluyente del comprobante rechaza con su código en las Obs del detalle y no consume el número")
    void rechazaYNoConsumeElNumero(String caso, UnaryOperator<PedidoDePrueba> cambio, int codigo) {
        FeCaeResponse respuesta = solicitar(cambio.apply(facturaB()));

        rechazadoCon(respuesta, codigo);
        // Un rechazo trae solo los códigos de rechazo: ninguna observación de las que aprueban.
        assertThat(respuesta.feDetResp().observaciones()).allSatisfy(o -> assertThat(o.code()).isNotIn(OBSERVACIONES));
        assertThat(ultimo(6)).isZero();
        assertThat(simulador.feCompConsultar(auth, 6, 1, 1).resultGet()).isNull();
        aprobado(solicitar(facturaB().numero(1)));
    }

    static Stream<Arguments> aprobados() {
        return Stream.of(
                caso("10016: CbteFch N-5 con concepto 1", p -> p.cbteFch(HOY.minusDays(5)), 0),
                caso("10016 y 10152: CbteFch N+3, último día del mes", p -> p.cbteFch(HOY.plusDays(3)), 0),
                caso("10016: CbteFch N-10 con concepto 2", p -> p.concepto(2).cbteFch(HOY.minusDays(10)), 0),
                caso("10048: ImpTotal 0,01 más que la suma", p -> p.impTotal("121.01"), 0),
                caso("10048: 0,0083 % sobre un total grande", p -> p.documento(96, DNI)
                        .importes("1210100.00", "1000000.00", "210000.00").alicuotas(alicuota(5, "1000000.00", "210000.00")),
                        0),
                caso("10051: Importe 0,01 más que el 21 % de BaseImp", p -> p.importes("121.01", "100.00", "21.01")
                        .alicuotas(alicuota(5, "100.00", "21.01")), 0),
                caso("10023: con dos alícuotas, difiere en 0,02", p -> p.importes("231.52", "200.00", "31.52")
                        .alicuotas(alicuota(4, "100.00", "10.50"), alicuota(5, "100.00", "21.00")), 0),
                caso("10061: con dos alícuotas, difiere en 0,02", p -> p.importes("231.52", "200.02", "31.50")
                        .alicuotas(alicuota(4, "100.00", "10.50"), alicuota(5, "100.00", "21.00")), 0),
                caso("10051 y 10061 no aplican a la NC B (8)", p -> p.tipo(8).periodo(OCTUBRE)
                        .importes("121.10", "100.05", "21.05").alicuotas(alicuota(5, "100.00", "21.05")), 0),
                caso("10197: NC B con PeriodoAsoc", p -> p.tipo(8).periodo(OCTUBRE), 0),
                caso("10015: Factura B a un DNI", p -> p.documento(96, DNI), 0),
                caso("10015: Factura B a una CUIT del padrón", p -> p.documento(80, CUIT_RECEPTOR).condicion(4), 0),
                caso("10015: Factura B a No Categorizado (80 y 23000000000)", p -> p.documento(80, 23000000000L).condicion(7),
                        0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aprobados")
    @DisplayName("lo que entra en la regla o en la tolerancia se autoriza")
    void loQueEntraSeAutoriza(String caso, UnaryOperator<PedidoDePrueba> cambio, int sinCodigo) {
        PedidoDePrueba pedido = cambio.apply(facturaB());
        int cbteTipo = pedido.armar().feCabReq().cbteTipo();

        aprobado(solicitar(pedido));
        assertThat(ultimo(cbteTipo)).isEqualTo(1);
    }

    @Test
    @DisplayName("10007: un CbteTipo que no es de ninguna clase falla en la cabecera: Errors, R y sin detalle")
    void cbteTipoSinClaseFallaEnLaCabecera() {
        FeCaeResponse respuesta = solicitar(facturaB().tipo(99));

        rechazadoEnLaCabeceraCon(respuesta, 10007);
        assertThat(respuesta.feCabResp().cbteTipo()).isEqualTo(99);
        assertThat(ultimo(99)).isZero();
        aprobado(solicitar(facturaB().numero(1)));
    }

    @Test
    @DisplayName("10016: un número ya autorizado no se autoriza de nuevo")
    void numeroRepetido() {
        aprobado(solicitar(facturaB().numero(1)));

        rechazadoCon(solicitar(facturaB().numero(1)), 10016);
        assertThat(ultimo(6)).isEqualTo(1);
    }

    @Test
    @DisplayName("10016: la CbteFch no puede ser anterior a la del último autorizado de la serie")
    void cbteFchAnteriorALaDelUltimo() {
        aprobado(solicitar(facturaB().numero(1).cbteFch(HOY.plusDays(2))));

        rechazadoCon(solicitar(facturaB().numero(2).cbteFch(HOY.plusDays(1))), 10016);
        aprobado(solicitar(facturaB().numero(2).cbteFch(HOY.plusDays(2))));
    }

    @Test
    @DisplayName("10015: desde el tope de la RG 4444, la Factura B a DocTipo 99 se rechaza; un centavo menos, no")
    void topeDeLaRg4444() {
        PedidoDePrueba enElTope = facturaB().desglose(desglose(topeRg4444));
        PedidoDePrueba unCentavoMenos = facturaB().desglose(desglose(topeRg4444.subtract(new BigDecimal("0.01"))));

        rechazadoCon(solicitar(enElTope), 10015);
        aprobado(solicitar(unCentavoMenos));
        aprobado(solicitar(facturaB().numero(2).desglose(desglose(topeRg4444)).documento(96, DNI)));
    }

    private static DesgloseFiscal desglose(BigDecimal total) {
        return DesgloseFiscal.de(Letra.B, List.of(new RenglonFiscal(Alicuota.IVA_21, total)));
    }
}
