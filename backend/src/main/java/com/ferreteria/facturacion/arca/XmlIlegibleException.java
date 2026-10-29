package com.ferreteria.facturacion.arca;

/**
 * Un XML que no se puede leer: mal formado, con DOCTYPE o entidades (rechazados siempre, por XXE), que no valida contra el
 * XSD de WSFEv1, o que no tiene la forma esperada. El mensaje nunca lleva el documento: a lo sumo el nombre de un elemento.
 */
public class XmlIlegibleException extends RuntimeException {

    public XmlIlegibleException(String mensaje) {
        super(mensaje);
    }

    public XmlIlegibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
