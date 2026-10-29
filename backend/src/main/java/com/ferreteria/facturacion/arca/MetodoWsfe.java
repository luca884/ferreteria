package com.ferreteria.facturacion.arca;

/**
 * Los métodos de WSFEv1 que usa el cliente, con su nombre en el WSDL. Cada uno se pide con
 * {@code SOAPAction: "http://ar.gov.afip.dif.FEV1/<Metodo>"} (C7) y responde {@code <Metodo>Response} con
 * {@code <Metodo>Result} adentro.
 */
public enum MetodoWsfe {
    FE_DUMMY("FEDummy"),
    FE_PARAM_GET_PTOS_VENTA("FEParamGetPtosVenta"),
    FE_COMP_ULTIMO_AUTORIZADO("FECompUltimoAutorizado"),
    FE_CAE_SOLICITAR("FECAESolicitar"),
    FE_COMP_CONSULTAR("FECompConsultar");

    /** El namespace de WSFEv1 (el {@code targetNamespace} del XSD). */
    public static final String NAMESPACE = "http://ar.gov.afip.dif.FEV1/";

    private final String nombre;

    MetodoWsfe(String nombre) {
        this.nombre = nombre;
    }

    /** El nombre del método en el WSDL, por ejemplo {@code FECAESolicitar}. */
    public String nombre() {
        return nombre;
    }

    /** La acción SOAP, sin comillas (las pone el transporte). */
    public String soapAction() {
        return NAMESPACE + nombre;
    }

    /** El elemento de la respuesta dentro del {@code soap:Body}. */
    public String respuesta() {
        return nombre + "Response";
    }

    /** El elemento con el resultado dentro de la respuesta. */
    public String resultado() {
        return nombre + "Result";
    }

    @Override
    public String toString() {
        return nombre;
    }
}
