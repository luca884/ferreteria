package com.ferreteria.facturacion.wsfe;

/**
 * El puerto hacia WSFEv1 (factura electrónica de ARCA, manual «RG 4291 – Proyecto FE v4.8»), en sus términos: las tres
 * operaciones que usa el motor de numeración, con los nombres y campos del manual. Lo implementan el simulador
 * ({@code facturacion.simulador}, solo ambiente SIMULADOR) y, desde las ramas 5 y 6, el cliente real (solo HOMOLOGACION y
 * PRODUCCION). Cada implementación rechaza el ambiente que no atiende con {@link IllegalArgumentException}, antes de mandar
 * nada: lo simulado nunca llega a ARCA real.
 *
 * <p>Cualquier operación puede terminar en {@link PedidoNoLlegoException} (seguro no llegó) o en
 * {@link ResultadoInciertoException} (no se sabe si se procesó). Las validaciones de ARCA no son excepciones: vuelven en la
 * respuesta.
 */
public interface ServicioWsfe {

    /**
     * {@code FECompUltimoAutorizado}: el último número autorizado de la serie (CUIT emisora, punto de venta y tipo).
     *
     * @throws PedidoNoLlegoException si el pedido seguro no llegó
     * @throws ResultadoInciertoException si no se sabe si se procesó
     */
    FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth auth, int ptoVta, int cbteTipo);

    /**
     * {@code FECAESolicitar}: pide el CAE de un comprobante.
     *
     * @throws PedidoNoLlegoException si el pedido seguro no llegó: ARCA no autorizó nada
     * @throws ResultadoInciertoException si no se sabe si ARCA lo autorizó
     */
    FeCaeResponse feCaeSolicitar(Auth auth, FeCaeReq feCaeReq);

    /**
     * {@code FECompConsultar}: un comprobante autorizado de la serie, con todos sus campos.
     *
     * @throws PedidoNoLlegoException si el pedido seguro no llegó
     * @throws ResultadoInciertoException si no se sabe si se procesó
     */
    FeCompConsultarResponse feCompConsultar(Auth auth, int cbteTipo, int ptoVta, long cbteNro);
}
