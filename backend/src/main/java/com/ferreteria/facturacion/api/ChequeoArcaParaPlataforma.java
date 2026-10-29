package com.ferreteria.facturacion.api;

/**
 * Puerto de {@code facturacion} para el chequeo del super admin (rama 6, C2, {@code POST /api/admin/arca/fedummy}):
 * {@code FEDummy} de WSFEv1 contra homologación y producción, desde el servidor desplegado, para probar la red y el TLS.
 * No usa certificados, tickets ni datos de ninguna ferretería. Solo tipos del JDK: la plataforma no conoce el cliente de
 * ARCA ni sus hosts.
 */
public interface ChequeoArcaParaPlataforma {

    /** {@code FEDummy} en homologación y después en producción; una falla de uno no corta el otro. */
    ChequeoFeDummy feDummy();

    /**
     * El chequeo de los dos ambientes reales.
     *
     * @param homologacion el de {@code wswhomo}
     * @param produccion el de {@code servicios1}
     */
    record ChequeoFeDummy(ResultadoFeDummy homologacion, ResultadoFeDummy produccion) {}

    /**
     * Lo que dio {@code FEDummy} en un ambiente.
     *
     * @param ok si los tres servidores dijeron «OK»
     * @param appServer lo que dijo {@code AppServer}; null si no hubo respuesta
     * @param dbServer lo que dijo {@code DbServer}; null si no hubo respuesta
     * @param authServer lo que dijo {@code AuthServer}; null si no hubo respuesta
     * @param milisegundos cuánto tardó la llamada; null si no hubo respuesta
     * @param error el motivo legible («No se pudo conectar con ARCA (timeout de conexión).», «Falló el handshake TLS.»);
     *     null si anduvo
     */
    record ResultadoFeDummy(boolean ok, String appServer, String dbServer, String authServer, Long milisegundos,
            String error) {}
}
