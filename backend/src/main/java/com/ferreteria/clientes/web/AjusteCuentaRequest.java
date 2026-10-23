package com.ferreteria.clientes.web;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.service.AjustePedido;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que manda la pantalla para ajustar la cuenta de un cliente (GDS-20). No tiene ferretería ni usuario (salen del JWT) ni
 * cliente (sale de la ruta). Aquí NO se valida nada: el servicio valida todo junto y devuelve un error por campo (un 400 es un
 * 400 venga de donde venga). {@code tipo} viaja como texto para que uno inválido diga «Elegí...» y no el error del enum.
 * {@code imputaciones} ausente o vacía es válido; solo un Descuento las admite (con un Recargo es 400).
 */
public record AjusteCuentaRequest(String clave, String tipo, BigDecimal importe, String motivo,
        List<ImputacionRequest> imputaciones) {

    /** Cuánto del Descuento se aplica a una deuda: exactamente uno de {@code comprobanteId} y {@code ajusteId} (un Recargo). */
    public record ImputacionRequest(Long comprobanteId, Long ajusteId, BigDecimal importe) {}

    public AjustePedido aPedido(Long clienteId) {
        return new AjustePedido(clave, clienteId, tipo, importe, motivo,
                imputaciones == null ? List.of()
                        : imputaciones.stream().map(i -> i == null ? new Imputar(null, null, null)
                                : new Imputar(i.comprobanteId(), i.ajusteId(), i.importe())).toList());
    }
}
