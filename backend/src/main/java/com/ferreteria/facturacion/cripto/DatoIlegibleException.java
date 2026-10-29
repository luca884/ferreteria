package com.ferreteria.facturacion.cripto;

/**
 * Un dato cifrado que no se puede leer (S6): de otra versión del formato, cifrado con una clave maestra que el servidor no
 * tiene, o que no se descifra (tocado, cortado, o con otro AAD). El mensaje nunca lleva el dato ni la clave. Deja ILEGIBLE
 * solo la credencial de esa ferretería: las demás siguen igual.
 */
public class DatoIlegibleException extends RuntimeException {

    /** Por qué no se pudo leer. */
    public enum Motivo {
        /** El primer byte no es una versión del formato que este código conozca. */
        VERSION_DESCONOCIDA,
        /** El id de clave no es el de ARCA_CLAVE_MAESTRA ni el de ARCA_CLAVE_MAESTRA_ANTERIOR. */
        CLAVE_DESCONOCIDA,
        /** Corto, tocado o con otro AAD: el tag de GCM no verifica. */
        NO_SE_DESCIFRA
    }

    private final Motivo motivo;

    public DatoIlegibleException(Motivo motivo, ContextoDeCifrado contexto) {
        super("No se pudo leer el dato cifrado " + contexto.proposito() + " de la ferretería " + contexto.ferreteriaId()
                + ": " + motivo);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
