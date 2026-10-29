package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;

/**
 * De dónde saca {@link ClienteWsfe} el TA para llamar a un servicio de ARCA: el token, el sign y la CUIT de la credencial
 * activa de la ferretería (la del {@code TenantContext} que fija el ejecutor fiscal), para un ambiente y un servicio
 * ({@code "wsfe"}). La implementación real, {@link TicketsDeAcceso} (rama 6, T5), lo pide a WSAA con un lease en la base y
 * lo guarda cifrado.
 */
public interface ProveedorDeTicket {

    /**
     * Un TA vigente para ese ambiente y ese servicio.
     *
     * @throws PedidoNoLlegoException si no se pudo conseguir (WSAA no contestó, la credencial está bloqueada, hay que esperar):
     *     el pedido a WSFE no se manda
     */
    TicketDeAcceso ticket(Ambiente ambiente, String servicio);

    /**
     * Descarta el TA que ARCA rechazó (el 600 de WSFE), así el próximo {@link #ticket} saca otro. Recibe el rechazado para
     * no descartar uno más nuevo que otro pedido ya haya conseguido.
     */
    void descartar(Ambiente ambiente, String servicio, TicketDeAcceso rechazado);
}
