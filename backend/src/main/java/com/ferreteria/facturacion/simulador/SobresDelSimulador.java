package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.arca.ClienteWsaa;
import com.ferreteria.facturacion.arca.FeParamGetPtosVentaResponse;
import com.ferreteria.facturacion.arca.MetodoWsfe;
import com.ferreteria.facturacion.arca.PlantillasWsfe;
import com.ferreteria.facturacion.arca.PtoVenta;
import com.ferreteria.facturacion.arca.XmlSeguro;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.ResultGet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Las respuestas del simulador en el borde, armadas como texto con la forma del WSDL: lo que contestaría ARCA (rama 6, S6).
 * WSFEv1 en el orden de las secuencias del XSD ({@code wsfev1-2026-10-29.xsd}), con lo opcional que no viene sin escribir y
 * cada valor escapado; WSAA como Apache Axis ({@code loginCmsReturn} con el TA escapado, y el Fault con {@code ns1:}). Las
 * lee el mismo cliente que lee las de ARCA ({@code LectorWsfe} y {@code ClienteWsaa}).
 */
final class SobresDelSimulador {

    private static final String NS = MetodoWsfe.NAMESPACE;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final String INICIO_WSFE = "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\""
            + XmlSeguro.SOAP_ENV + "\"><soap:Body>";
    private static final String FIN_WSFE = "</soap:Body></soap:Envelope>";
    private static final String INICIO_WSAA = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\""
            + XmlSeguro.SOAP_ENV + "\"><soapenv:Body>";
    private static final String FIN_WSAA = "</soapenv:Body></soapenv:Envelope>";

    private SobresDelSimulador() {
    }

    // ---- WSAA ----

    /** {@code loginCmsResponse}: el {@code loginTicketResponse} escapado dentro de {@code loginCmsReturn}. */
    static String loginCms(String loginTicketResponse) {
        return INICIO_WSAA + "<loginCmsResponse xmlns=\"" + ClienteWsaa.NAMESPACE + "\"><loginCmsReturn>"
                + XmlSeguro.escapar(loginTicketResponse) + "</loginCmsReturn></loginCmsResponse>" + FIN_WSAA;
    }

    /** El SOAP Fault de WSAA, como Axis: el código con el prefijo {@code ns1:} y el texto. Va con status 500. */
    static String faultWsaa(String codigo, String texto) {
        return INICIO_WSAA + "<soapenv:Fault><faultcode xmlns:ns1=\"" + ClienteWsaa.NAMESPACE + "\">ns1:"
                + XmlSeguro.escapar(codigo) + "</faultcode><faultstring>" + XmlSeguro.escapar(texto)
                + "</faultstring></soapenv:Fault>" + FIN_WSAA;
    }

    // ---- WSFEv1 ----

    /** El SOAP Fault de WSFEv1 para un pedido que no se puede leer (como ASMX, {@code soap:Client}). Va con status 500. */
    static String faultWsfe(String texto) {
        return INICIO_WSFE + "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>" + XmlSeguro.escapar(texto)
                + "</faultstring></soap:Fault>" + FIN_WSFE;
    }

    /** {@code FEDummy}: los tres servidores OK. */
    static String feDummy() {
        Xml xml = new Xml(MetodoWsfe.FE_DUMMY);
        xml.campo("AppServer", "OK");
        xml.campo("DbServer", "OK");
        xml.campo("AuthServer", "OK");
        return xml.cerrar();
    }

    /** {@code FEParamGetPtosVenta}: {@code ResultGet} con los puntos (si hay) y {@code Errors}. */
    static String feParamGetPtosVenta(FeParamGetPtosVentaResponse respuesta) {
        Xml xml = new Xml(MetodoWsfe.FE_PARAM_GET_PTOS_VENTA);
        if (!respuesta.resultGet().isEmpty()) {
            xml.abrir("ResultGet");
            for (PtoVenta punto : respuesta.resultGet()) {
                xml.abrir("PtoVenta");
                xml.campo("Nro", punto.nro());
                xml.campo("EmisionTipo", punto.emisionTipo());
                xml.campo("Bloqueado", punto.bloqueado() ? "S" : "N");
                xml.campo("FchBaja", punto.fchBaja() == null ? "NULL" : fecha(punto.fchBaja()));
                xml.cerrar("PtoVenta");
            }
            xml.cerrar("ResultGet");
        }
        errores(xml, respuesta.errors());
        return xml.cerrar();
    }

    /** {@code FECompUltimoAutorizado}: {@code PtoVta}, {@code CbteTipo}, {@code CbteNro} y {@code Errors}. */
    static String feCompUltimoAutorizado(FeCompUltimoAutorizadoResponse respuesta) {
        Xml xml = new Xml(MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO);
        xml.campo("PtoVta", respuesta.ptoVta());
        xml.campo("CbteTipo", respuesta.cbteTipo());
        xml.campo("CbteNro", respuesta.cbteNro());
        errores(xml, respuesta.errors());
        return xml.cerrar();
    }

    /** {@code FECAESolicitar}: la cabecera, el detalle (si hay) y {@code Errors}. */
    static String feCaeSolicitar(FeCaeResponse respuesta) {
        Xml xml = new Xml(MetodoWsfe.FE_CAE_SOLICITAR);
        if (respuesta.feCabResp() != null) {
            cabecera(xml, respuesta.feCabResp());
        }
        if (respuesta.feDetResp() != null) {
            xml.abrir("FeDetResp");
            detalle(xml, respuesta.feDetResp());
            xml.cerrar("FeDetResp");
        }
        errores(xml, respuesta.errors());
        return xml.cerrar();
    }

    /** {@code FECompConsultar}: el comprobante ({@code ResultGet}, si hay) y {@code Errors}. */
    static String feCompConsultar(FeCompConsultarResponse respuesta) {
        Xml xml = new Xml(MetodoWsfe.FE_COMP_CONSULTAR);
        if (respuesta.resultGet() != null) {
            comprobante(xml, respuesta.resultGet());
        }
        errores(xml, respuesta.errors());
        return xml.cerrar();
    }

    /**
     * La respuesta de un método con {@code Auth} que el simulador no acepta (el 600, C4): solo {@code Errors}, la forma mínima
     * que permite el WSDL. {@code FECompUltimoAutorizado} lleva además sus tres campos obligatorios, en cero.
     */
    static String rechazoDelAuth(MetodoWsfe metodo, Err error) {
        return switch (metodo) {
            case FE_COMP_ULTIMO_AUTORIZADO -> feCompUltimoAutorizado(new FeCompUltimoAutorizadoResponse(0, 0, 0,
                    List.of(error)));
            case FE_PARAM_GET_PTOS_VENTA -> feParamGetPtosVenta(new FeParamGetPtosVentaResponse(List.of(), List.of(error)));
            case FE_CAE_SOLICITAR, FE_COMP_CONSULTAR -> {
                Xml xml = new Xml(metodo);
                errores(xml, List.of(error));
                yield xml.cerrar();
            }
            case FE_DUMMY -> throw new IllegalArgumentException("FEDummy no lleva Auth.");
        };
    }

    /** {@code FECAECabResponse}, con {@code Reproceso} N. */
    private static void cabecera(Xml xml, FeCabResp cabecera) {
        xml.abrir("FeCabResp");
        xml.campo("Cuit", cabecera.cuit());
        xml.campo("PtoVta", cabecera.ptoVta());
        xml.campo("CbteTipo", cabecera.cbteTipo());
        if (cabecera.fchProceso() != null) {
            xml.campo("FchProceso", fechaHora(cabecera.fchProceso()));
        }
        xml.campo("CantReg", cabecera.cantReg());
        if (cabecera.resultado() != null) {
            xml.campo("Resultado", cabecera.resultado().name());
        }
        xml.campo("Reproceso", "N");
        xml.cerrar("FeCabResp");
    }

    /** {@code FECAEDetResponse}: los campos de {@code FEDetResponse}, {@code CAE} y {@code CAEFchVto} (sin ellos con R). */
    private static void detalle(Xml xml, FeCaeDetResponse detalle) {
        xml.abrir("FECAEDetResponse");
        xml.campo("Concepto", detalle.concepto());
        xml.campo("DocTipo", detalle.docTipo());
        xml.campo("DocNro", detalle.docNro());
        xml.campo("CbteDesde", detalle.cbteDesde());
        xml.campo("CbteHasta", detalle.cbteHasta());
        if (detalle.cbteFch() != null) {
            xml.campo("CbteFch", fecha(detalle.cbteFch()));
        }
        if (detalle.resultado() != null) {
            xml.campo("Resultado", detalle.resultado().name());
        }
        observaciones(xml, detalle.observaciones());
        if (detalle.cae() != null) {
            xml.campo("CAE", detalle.cae());
        }
        if (detalle.caeFchVto() != null) {
            xml.campo("CAEFchVto", fecha(detalle.caeFchVto()));
        }
        xml.cerrar("FECAEDetResponse");
    }

    /** {@code FECompConsResponse}: los campos de {@code FECAEDetRequest} y después los del resultado. */
    private static void comprobante(Xml xml, ResultGet comprobante) {
        xml.abrir("ResultGet");
        xml.crudo(PlantillasWsfe.camposDelDetalle(comprobante.detalle()));
        if (comprobante.resultado() != null) {
            xml.campo("Resultado", comprobante.resultado().name());
        }
        if (comprobante.codAutorizacion() != null) {
            xml.campo("CodAutorizacion", comprobante.codAutorizacion());
        }
        if (comprobante.emisionTipo() != null) {
            xml.campo("EmisionTipo", comprobante.emisionTipo());
        }
        if (comprobante.fchVto() != null) {
            xml.campo("FchVto", fecha(comprobante.fchVto()));
        }
        if (comprobante.fchProceso() != null) {
            xml.campo("FchProceso", fechaHora(comprobante.fchProceso()));
        }
        observaciones(xml, comprobante.observaciones());
        xml.campo("PtoVta", comprobante.ptoVta());
        xml.campo("CbteTipo", comprobante.cbteTipo());
        xml.cerrar("ResultGet");
    }

    private static void observaciones(Xml xml, List<Obs> observaciones) {
        if (observaciones.isEmpty()) {
            return;
        }
        xml.abrir("Observaciones");
        for (Obs obs : observaciones) {
            xml.abrir("Obs");
            xml.campo("Code", obs.code());
            if (obs.msg() != null) {
                xml.campo("Msg", obs.msg());
            }
            xml.cerrar("Obs");
        }
        xml.cerrar("Observaciones");
    }

    private static void errores(Xml xml, List<Err> errores) {
        if (errores.isEmpty()) {
            return;
        }
        xml.abrir("Errors");
        for (Err err : errores) {
            xml.abrir("Err");
            xml.campo("Code", err.code());
            if (err.msg() != null) {
                xml.campo("Msg", err.msg());
            }
            xml.cerrar("Err");
        }
        xml.cerrar("Errors");
    }

    private static String fecha(LocalDate fecha) {
        return fecha.format(FECHA);
    }

    private static String fechaHora(LocalDateTime fechaHora) {
        return fechaHora.format(FECHA_HORA);
    }

    /** Una respuesta de WSFEv1 en armado: {@code <Metodo>Response} y {@code <Metodo>Result} abiertos en el Body. */
    private static final class Xml {

        private final MetodoWsfe metodo;
        private final StringBuilder xml = new StringBuilder(1024);

        Xml(MetodoWsfe metodo) {
            this.metodo = metodo;
            xml.append(INICIO_WSFE).append('<').append(metodo.respuesta()).append(" xmlns=\"").append(NS).append("\"><")
                    .append(metodo.resultado()).append('>');
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

        /** XML ya armado y escapado (los campos del detalle, de {@link PlantillasWsfe#camposDelDetalle}). */
        void crudo(String fragmento) {
            xml.append(fragmento);
        }

        String cerrar() {
            xml.append("</").append(metodo.resultado()).append("></").append(metodo.respuesta()).append('>')
                    .append(FIN_WSFE);
            return xml.toString();
        }
    }
}
