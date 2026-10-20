package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que pide una cobranza: la clave de idempotencia, el cliente, el medio, el total y a qué comprobantes se imputa (puede
 * ser ninguno: todo «a cuenta»). La ferretería y el usuario salen del contexto, no del pedido.
 */
public record CobranzaPedido(String clave, Long clienteId, MedioPago medio, BigDecimal total, List<Imputar> imputaciones) {

    /** Sin lista de imputaciones es lo mismo que una vacía (todo «a cuenta»): así comparar nunca revienta. */
    public CobranzaPedido {
        imputaciones = imputaciones == null ? List.of() : imputaciones;
    }

    /**
     * ¿Este pedido es el mismo que ya registró {@code recibo}? Compara cliente, medio, total y el CONJUNTO de imputaciones
     * (comprobante → importe), sin importar el orden y con {@code compareTo} (10 es igual a 10.00).
     */
    public boolean mismoContenido(Recibo recibo, List<ImputacionAsentada> asentadas) {
        if (!clienteId.equals(recibo.getClienteId()) || medio != recibo.getMedio() || total.compareTo(recibo.getTotal()) != 0) {
            return false;
        }
        Map<Long, BigDecimal> pedidas = new HashMap<>();
        imputaciones.forEach(i -> pedidas.merge(i.comprobanteId(), i.importe(), BigDecimal::add));
        Map<Long, BigDecimal> registradas = new HashMap<>();
        asentadas.forEach(i -> registradas.merge(i.comprobanteId(), i.importe(), BigDecimal::add));
        if (!pedidas.keySet().equals(registradas.keySet())) {
            return false;
        }
        return pedidas.entrySet().stream().allMatch(e -> e.getValue().compareTo(registradas.get(e.getKey())) == 0);
    }
}
