package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCabReq;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * El XML de cada pedido a WSFEv1, armado como texto (sin cliente generado del WSDL): un sobre SOAP 1.1 con el método en el
 * namespace {@code http://ar.gov.afip.dif.FEV1/} y cada valor escapado ({@link XmlSeguro#escapar}). El orden de los elementos
 * es el de las secuencias del XSD ({@code wsfev1-2026-10-29.xsd}), que no admite otro, y lo opcional que no viene no se
 * escribe (por ejemplo el {@code Iva} de la factura C o la {@code CbteFch} que pone ARCA). La plata va con
 * {@link BigDecimal#toPlainString()} y dos decimales; {@code MonCotiz}, con los seis que tiene el pedido.
 *
 * <p>{@code Auth} es {@code Token}, {@code Sign} y {@code Cuit}, en ese orden. La {@code Cuit} es la del registro, que llega
 * aparte del ticket (S17): quien la elige es {@link ClienteWsfe}.
 */
public final class PlantillasWsfe {

    private static final String INICIO = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"" + XmlSeguro.SOAP_ENV + "\"><soap:Body>";
    private static final String FIN = "</soap:Body></soap:Envelope>";
    private static final DateTimeFormatter FECHA = DateTimeFormatter.BASIC_ISO_DATE;

    private PlantillasWsfe() {
    }

    /** {@code FEDummy}: sin {@code Auth}, para ver si los servidores de ARCA andan. */
    public static String feDummy() {
        return INICIO + "<" + MetodoWsfe.FE_DUMMY.nombre() + " xmlns=\"" + MetodoWsfe.NAMESPACE + "\"/>" + FIN;
    }

    /** {@code FEParamGetPtosVenta}: los puntos de venta de la CUIT. */
    public static String feParamGetPtosVenta(TicketDeAcceso ticket, long cuit) {
        Sobre sobre = new Sobre(MetodoWsfe.FE_PARAM_GET_PTOS_VENTA);
        sobre.auth(ticket, cuit);
        return sobre.cerrar();
    }

    /** {@code FECompUltimoAutorizado}: {@code Auth}, {@code PtoVta} y {@code CbteTipo}. */
    public static String feCompUltimoAutorizado(TicketDeAcceso ticket, long cuit, int ptoVta, int cbteTipo) {
        Sobre sobre = new Sobre(MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO);
        sobre.auth(ticket, cuit);
        sobre.campo("PtoVta", ptoVta);
        sobre.campo("CbteTipo", cbteTipo);
        return sobre.cerrar();
    }

    /** {@code FECAESolicitar}: {@code Auth} y {@code FeCAEReq} con la cabecera y el único detalle. */
    public static String feCaeSolicitar(TicketDeAcceso ticket, long cuit, FeCaeReq pedido) {
        Objects.requireNonNull(pedido, "el pedido");
        Sobre sobre = new Sobre(MetodoWsfe.FE_CAE_SOLICITAR);
        sobre.auth(ticket, cuit);
        sobre.abrir("FeCAEReq");
        cabecera(sobre, pedido.feCabReq());
        sobre.abrir("FeDetReq");
        detalle(sobre, pedido.feDetReq());
        sobre.cerrar("FeDetReq");
        sobre.cerrar("FeCAEReq");
        return sobre.cerrar();
    }

    /** {@code FECompConsultar}: {@code Auth} y {@code FeCompConsReq{CbteTipo, CbteNro, PtoVta}}. */
    public static String feCompConsultar(TicketDeAcceso ticket, long cuit, int cbteTipo, int ptoVta, long cbteNro) {
        Sobre sobre = new Sobre(MetodoWsfe.FE_COMP_CONSULTAR);
        sobre.auth(ticket, cuit);
        sobre.abrir("FeCompConsReq");
        sobre.campo("CbteTipo", cbteTipo);
        sobre.campo("CbteNro", cbteNro);
        sobre.campo("PtoVta", ptoVta);
        sobre.cerrar("FeCompConsReq");
        return sobre.cerrar();
    }

    /** {@code FECAECabRequest}: {@code CantReg}, {@code PtoVta}, {@code CbteTipo}. */
    private static void cabecera(Sobre sobre, FeCabReq cabecera) {
        sobre.abrir("FeCabReq");
        sobre.campo("CantReg", cabecera.cantReg());
        sobre.campo("PtoVta", cabecera.ptoVta());
        sobre.campo("CbteTipo", cabecera.cbteTipo());
        sobre.cerrar("FeCabReq");
    }

    /**
     * {@code FECAEDetRequest} en el orden de {@code FEDetRequest}: los campos fijos, la moneda, la condición del receptor
     * (RG 5616), {@code CbtesAsoc}, {@code Iva} y {@code PeriodoAsoc}. Sin tributos, opcionales, compradores ni actividades.
     */
    private static void detalle(Sobre sobre, FeCaeDetRequest detalle) {
        sobre.abrir("FECAEDetRequest");
        sobre.campo("Concepto", detalle.concepto());
        sobre.campo("DocTipo", detalle.docTipo());
        sobre.campo("DocNro", detalle.docNro());
        sobre.campo("CbteDesde", detalle.cbteDesde());
        sobre.campo("CbteHasta", detalle.cbteHasta());
        if (detalle.cbteFch() != null) {
            sobre.campo("CbteFch", fecha(detalle.cbteFch()));
        }
        sobre.campo("ImpTotal", plata(detalle.impTotal()));
        sobre.campo("ImpTotConc", plata(detalle.impTotConc()));
        sobre.campo("ImpNeto", plata(detalle.impNeto()));
        sobre.campo("ImpOpEx", plata(detalle.impOpEx()));
        sobre.campo("ImpTrib", plata(detalle.impTrib()));
        sobre.campo("ImpIVA", plata(detalle.impIva()));
        sobre.campo("MonId", detalle.monId());
        sobre.campo("MonCotiz", detalle.monCotiz().toPlainString());
        if (detalle.condicionIvaReceptorId() != null) {
            sobre.campo("CondicionIVAReceptorId", detalle.condicionIvaReceptorId());
        }
        if (!detalle.cbtesAsoc().isEmpty()) {
            sobre.abrir("CbtesAsoc");
            detalle.cbtesAsoc().forEach(asociado -> asociado(sobre, asociado));
            sobre.cerrar("CbtesAsoc");
        }
        if (!detalle.iva().isEmpty()) {
            sobre.abrir("Iva");
            detalle.iva().forEach(alicuota -> alicuota(sobre, alicuota));
            sobre.cerrar("Iva");
        }
        if (detalle.periodoAsoc() != null) {
            periodo(sobre, detalle.periodoAsoc());
        }
        sobre.cerrar("FECAEDetRequest");
    }

    private static void asociado(Sobre sobre, CbteAsoc asociado) {
        sobre.abrir("CbteAsoc");
        sobre.campo("Tipo", asociado.tipo());
        sobre.campo("PtoVta", asociado.ptoVta());
        sobre.campo("Nro", asociado.nro());
        if (asociado.cuit() != null) {
            sobre.campo("Cuit", asociado.cuit());
        }
        if (asociado.cbteFch() != null) {
            sobre.campo("CbteFch", fecha(asociado.cbteFch()));
        }
        sobre.cerrar("CbteAsoc");
    }

    private static void alicuota(Sobre sobre, AlicIva alicuota) {
        sobre.abrir("AlicIva");
        sobre.campo("Id", alicuota.id());
        sobre.campo("BaseImp", plata(alicuota.baseImp()));
        sobre.campo("Importe", plata(alicuota.importe()));
        sobre.cerrar("AlicIva");
    }

    private static void periodo(Sobre sobre, PeriodoAsoc periodo) {
        sobre.abrir("PeriodoAsoc");
        sobre.campo("FchDesde", fecha(periodo.fchDesde()));
        sobre.campo("FchHasta", fecha(periodo.fchHasta()));
        sobre.cerrar("PeriodoAsoc");
    }

    /** Un importe como lo espera ARCA: sin notación científica y con dos decimales. */
    private static String plata(BigDecimal importe) {
        return importe.setScale(2).toPlainString();
    }

    private static String fecha(LocalDate fecha) {
        return fecha.format(FECHA);
    }

    /** Un sobre en armado: el método abierto en el Body, y cada elemento con su valor escapado. */
    private static final class Sobre {

        private final MetodoWsfe metodo;
        private final StringBuilder xml = new StringBuilder(1024);

        Sobre(MetodoWsfe metodo) {
            this.metodo = metodo;
            xml.append(INICIO).append('<').append(metodo.nombre()).append(" xmlns=\"").append(MetodoWsfe.NAMESPACE)
                    .append("\">");
        }

        void auth(TicketDeAcceso ticket, long cuit) {
            Objects.requireNonNull(ticket, "el ticket de acceso");
            abrir("Auth");
            campo("Token", ticket.token());
            campo("Sign", ticket.sign());
            campo("Cuit", cuit);
            cerrar("Auth");
        }

        void abrir(String elemento) {
            xml.append('<').append(elemento).append('>');
        }

        void cerrar(String elemento) {
            xml.append("</").append(elemento).append('>');
        }

        void campo(String elemento, long valor) {
            campo(elemento, Long.toString(valor));
        }

        void campo(String elemento, String valor) {
            abrir(elemento);
            xml.append(XmlSeguro.escapar(valor));
            cerrar(elemento);
        }

        String cerrar() {
            xml.append("</").append(metodo.nombre()).append('>').append(FIN);
            return xml.toString();
        }
    }
}
