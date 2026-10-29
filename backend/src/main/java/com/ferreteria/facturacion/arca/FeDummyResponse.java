package com.ferreteria.facturacion.arca;

/**
 * La respuesta de {@code FEDummy} ({@code FEDummyResult}, tipo {@code DummyResponse} del WSDL): el estado de los tres
 * servidores de WSFEv1. No lleva {@code Auth}: sirve para probar la red y el TLS.
 *
 * @param appServer {@code AppServer}: «OK» si anda; null si no vino
 * @param dbServer {@code DbServer}
 * @param authServer {@code AuthServer}
 */
public record FeDummyResponse(String appServer, String dbServer, String authServer) {

    /** Si los tres servidores dicen «OK». */
    public boolean todoOk() {
        return "OK".equals(appServer) && "OK".equals(dbServer) && "OK".equals(authServer);
    }
}
