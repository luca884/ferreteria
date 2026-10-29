package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.wsfe.PedidoDePrueba.alicuota;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;

/**
 * S4 y C5: el XML de cada pedido a WSFEv1 sale de una plantilla de texto, con cada valor escapado, y valida contra el XSD del
 * WSDL (el hijo del {@code soap:Body}, no el {@code Envelope}). El orden de los elementos es el de las secuencias del XSD, y
 * la plata va con dos decimales.
 */
class PlantillasWsfeTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 29);
    private static final long CUIT_DEL_REGISTRO = 30999999995L;
    private static final TicketDeAcceso TICKET = new TicketDeAcceso("token-de-prueba", "sign-de-prueba", 20222222223L);

    /** Factura B con dos alícuotas: $ 100 al 21 % y $ 100 al 10,5 %. */
    private static FeCaeReq facturaBConDosAlicuotas() {
        return PedidoDePrueba.facturaB(HOY).numero(42)
                .alicuotas(alicuota(5, "100.00", "21.00"), alicuota(4, "100.00", "10.50"))
                .importes("231.50", "200.00", "31.50").armar();
    }

    /** Nota de crédito B que ajusta la Factura B 41 del punto 1. */
    private static FeCaeReq notaDeCreditoConAsociado() {
        return PedidoDePrueba.facturaB(HOY).tipo(8).numero(7)
                .asociados(new CbteAsoc(6, 1, 41, String.valueOf(CUIT_DEL_REGISTRO), HOY.minusDays(3))).armar();
    }

    /** Factura C (Monotributo): el neto es el total, sin IVA ni array {@code Iva}. */
    private static FeCaeReq facturaCSinIva() {
        return PedidoDePrueba.facturaB(HOY).tipo(11).importes("121.00", "121.00", "0.00").alicuotas().armar();
    }

    static Stream<Arguments> pedidos() {
        return Stream.of(
                Arguments.of("FEDummy", PlantillasWsfe.feDummy()),
                Arguments.of("FEParamGetPtosVenta", PlantillasWsfe.feParamGetPtosVenta(TICKET, CUIT_DEL_REGISTRO)),
                Arguments.of("FECompUltimoAutorizado",
                        PlantillasWsfe.feCompUltimoAutorizado(TICKET, CUIT_DEL_REGISTRO, 1, 6)),
                Arguments.of("FECAESolicitar",
                        PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, facturaBConDosAlicuotas())),
                Arguments.of("FECAESolicitar",
                        PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, notaDeCreditoConAsociado())),
                Arguments.of("FECAESolicitar", PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, facturaCSinIva())),
                Arguments.of("FECompConsultar", PlantillasWsfe.feCompConsultar(TICKET, CUIT_DEL_REGISTRO, 6, 1, 41)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("pedidos")
    @DisplayName("cada pedido es un sobre SOAP 1.1 cuyo hijo del Body valida contra el XSD de WSFEv1")
    void cadaPedidoValidaContraElXsd(String metodo, String sobre) {
        Element pedido = EsquemaWsfe.validarSobre(sobre);

        assertThat(pedido.getLocalName()).isEqualTo(metodo);
        assertThat(pedido.getNamespaceURI()).isEqualTo(MetodoWsfe.NAMESPACE);
    }

    @Test
    @DisplayName("FECAESolicitar lleva Auth{Token, Sign, Cuit del registro}, la cabecera y el detalle en el orden del XSD, "
            + "con la plata en dos decimales y el array de IVA")
    void elDetalleVaEnElOrdenDelXsd() {
        Element pedido = EsquemaWsfe.validarSobre(
                PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, facturaBConDosAlicuotas()));

        Element auth = XmlSeguro.hijo(pedido, "Auth").orElseThrow();
        assertThat(nombres(auth)).containsExactly("Token", "Sign", "Cuit");
        assertThat(XmlSeguro.texto(auth, "Token")).hasValue("token-de-prueba");
        assertThat(XmlSeguro.texto(auth, "Sign")).hasValue("sign-de-prueba");
        // S17: la CUIT del Auth es la del registro, no la del ticket.
        assertThat(XmlSeguro.texto(auth, "Cuit")).hasValue("30999999995");

        Element feCaeReq = XmlSeguro.hijo(pedido, "FeCAEReq").orElseThrow();
        Element cabecera = XmlSeguro.hijo(feCaeReq, "FeCabReq").orElseThrow();
        assertThat(nombres(cabecera)).containsExactly("CantReg", "PtoVta", "CbteTipo");
        assertThat(XmlSeguro.texto(cabecera, "CantReg")).hasValue("1");
        assertThat(XmlSeguro.texto(cabecera, "PtoVta")).hasValue("1");
        assertThat(XmlSeguro.texto(cabecera, "CbteTipo")).hasValue("6");

        Element detalle = XmlSeguro.hijo(XmlSeguro.hijo(feCaeReq, "FeDetReq").orElseThrow(), "FECAEDetRequest")
                .orElseThrow();
        assertThat(nombres(detalle)).containsExactly("Concepto", "DocTipo", "DocNro", "CbteDesde", "CbteHasta", "CbteFch",
                "ImpTotal", "ImpTotConc", "ImpNeto", "ImpOpEx", "ImpTrib", "ImpIVA", "MonId", "MonCotiz",
                "CondicionIVAReceptorId", "Iva");
        assertThat(XmlSeguro.texto(detalle, "CbteDesde")).hasValue("42");
        assertThat(XmlSeguro.texto(detalle, "CbteFch")).hasValue("20261029");
        assertThat(XmlSeguro.texto(detalle, "ImpTotal")).hasValue("231.50");
        assertThat(XmlSeguro.texto(detalle, "ImpTotConc")).hasValue("0.00");
        assertThat(XmlSeguro.texto(detalle, "ImpNeto")).hasValue("200.00");
        assertThat(XmlSeguro.texto(detalle, "ImpIVA")).hasValue("31.50");
        assertThat(XmlSeguro.texto(detalle, "MonId")).hasValue("PES");
        assertThat(XmlSeguro.texto(detalle, "MonCotiz")).hasValue("1.000000");
        assertThat(XmlSeguro.texto(detalle, "CondicionIVAReceptorId")).hasValue("5");
        List<Element> alicuotas = XmlSeguro.hijos(XmlSeguro.hijo(detalle, "Iva").orElseThrow(), "AlicIva");
        assertThat(alicuotas).hasSize(2);
        assertThat(nombres(alicuotas.get(1))).containsExactly("Id", "BaseImp", "Importe");
        assertThat(XmlSeguro.texto(alicuotas.get(1), "Id")).hasValue("4");
        assertThat(XmlSeguro.texto(alicuotas.get(1), "BaseImp")).hasValue("100.00");
        assertThat(XmlSeguro.texto(alicuotas.get(1), "Importe")).hasValue("10.50");
    }

    @Test
    @DisplayName("la nota de crédito lleva CbtesAsoc antes de Iva, y la factura C no lleva Iva")
    void asociadosYFacturaC() {
        Element nota = detalle(PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, notaDeCreditoConAsociado()));
        assertThat(nombres(nota)).endsWith("CondicionIVAReceptorId", "CbtesAsoc", "Iva");
        Element asociado = XmlSeguro.hijo(XmlSeguro.hijo(nota, "CbtesAsoc").orElseThrow(), "CbteAsoc").orElseThrow();
        assertThat(nombres(asociado)).containsExactly("Tipo", "PtoVta", "Nro", "Cuit", "CbteFch");
        assertThat(XmlSeguro.texto(asociado, "Nro")).hasValue("41");
        assertThat(XmlSeguro.texto(asociado, "CbteFch")).hasValue("20261026");

        Element facturaC = detalle(PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, facturaCSinIva()));
        assertThat(nombres(facturaC)).doesNotContain("Iva").endsWith("MonCotiz", "CondicionIVAReceptorId");
        assertThat(XmlSeguro.texto(facturaC, "ImpIVA")).hasValue("0.00");
    }

    @Test
    @DisplayName("FECompConsultar y FECompUltimoAutorizado llevan sus campos en el orden del XSD")
    void consultas() {
        Element consulta = EsquemaWsfe.validarSobre(PlantillasWsfe.feCompConsultar(TICKET, CUIT_DEL_REGISTRO, 6, 1, 41));
        Element pedido = XmlSeguro.hijo(consulta, "FeCompConsReq").orElseThrow();
        assertThat(nombres(pedido)).containsExactly("CbteTipo", "CbteNro", "PtoVta");
        assertThat(XmlSeguro.texto(pedido, "CbteNro")).hasValue("41");

        Element ultimo = EsquemaWsfe.validarSobre(PlantillasWsfe.feCompUltimoAutorizado(TICKET, CUIT_DEL_REGISTRO, 3, 11));
        assertThat(nombres(ultimo)).containsExactly("Auth", "PtoVta", "CbteTipo");
        assertThat(XmlSeguro.texto(ultimo, "PtoVta")).hasValue("3");
        assertThat(XmlSeguro.texto(ultimo, "CbteTipo")).hasValue("11");
    }

    @Test
    @DisplayName("cada valor va escapado: un token con < & > \" ' se lee de vuelta igual")
    void escapaCadaValor() {
        TicketDeAcceso raro = new TicketDeAcceso("a<b&c>d", "\"firma\" 'rara'", 20222222223L);

        String sobre = PlantillasWsfe.feParamGetPtosVenta(raro, CUIT_DEL_REGISTRO);

        assertThat(sobre).contains("a&lt;b&amp;c&gt;d").contains("&quot;firma&quot; &apos;rara&apos;");
        Element auth = XmlSeguro.hijo(EsquemaWsfe.validarSobre(sobre), "Auth").orElseThrow();
        assertThat(XmlSeguro.texto(auth, "Token")).hasValue("a<b&c>d");
        assertThat(XmlSeguro.texto(auth, "Sign")).hasValue("\"firma\" 'rara'");
    }

    @Test
    @DisplayName("el XSD rechaza un pedido fuera de orden (ImpNeto antes que ImpTotal)")
    void elXsdRechazaUnPedidoFueraDeOrden() {
        String bien = PlantillasWsfe.feCaeSolicitar(TICKET, CUIT_DEL_REGISTRO, facturaCSinIva());
        String desordenado = bien.replace("<ImpTotal>121.00</ImpTotal><ImpTotConc>0.00</ImpTotConc><ImpNeto>121.00</ImpNeto>",
                "<ImpNeto>121.00</ImpNeto><ImpTotal>121.00</ImpTotal><ImpTotConc>0.00</ImpTotConc>");
        assertThat(desordenado).isNotEqualTo(bien);

        assertThatThrownBy(() -> EsquemaWsfe.validarSobre(desordenado)).isInstanceOf(XmlIlegibleException.class)
                .hasMessageContaining("XSD de WSFEv1");
    }

    private static Element detalle(String sobre) {
        Element feCaeReq = XmlSeguro.hijo(EsquemaWsfe.validarSobre(sobre), "FeCAEReq").orElseThrow();
        return XmlSeguro.hijo(XmlSeguro.hijo(feCaeReq, "FeDetReq").orElseThrow(), "FECAEDetRequest").orElseThrow();
    }

    private static List<String> nombres(Element padre) {
        return XmlSeguro.elementos(padre).stream().map(Element::getLocalName).toList();
    }
}
