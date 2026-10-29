package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import java.util.List;
import java.util.Objects;

/**
 * El pedido de alta de un registro fiscal: lo que la venta (rama 3) le pasa al motor, con tipos simples (ids, el receptor y
 * los renglones; nada de entidades de ventas ni de clientes). El resto lo pone el motor al registrar: el ambiente y el punto
 * de venta de {@code ConfigArca}, el encuadre y el CbteTipo de {@code EncuadreFiscal}, el desglose de
 * {@code DesgloseFiscal}, la copia del emisor de los datos fiscales de la ferretería y la hora del {@code Clock}. Siempre es
 * una factura: la nota de crédito (rama 8) no se arma con un alta, porque no recalcula nada; copia todo, con el asociado, del
 * registro de su factura ({@code ComprobanteFiscal.notaDeCredito}).
 *
 * @param comprobanteId la venta (de la ferretería del request) que se factura
 * @param receptor quien la recibe ({@link ReceptorFiscal#sinCliente()} si no hay cliente)
 * @param renglones los renglones con su alícuota y su total con IVA (al menos uno)
 */
public record AltaFiscal(long comprobanteId, ReceptorFiscal receptor, List<RenglonFiscal> renglones) {

    public AltaFiscal {
        Objects.requireNonNull(receptor, "Falta el receptor del comprobante.");
        Objects.requireNonNull(renglones, "Faltan los renglones del comprobante.");
        if (renglones.isEmpty()) {
            throw new IllegalArgumentException("Un comprobante fiscal lleva al menos un renglón.");
        }
        renglones = List.copyOf(renglones);
    }
}
