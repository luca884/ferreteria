package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * El cliente que lleva una venta, un presupuesto o un remito (CL-004). Con {@code clienteId} el cliente tiene que ser
 * uno activo de la ferretería: el nombre del documento sale de su razón social, y el texto libre se ignora. Sin
 * {@code clienteId} vale el texto libre (o nada: Consumidor Final). El documento guarda el id y una copia del nombre.
 */
@Component
public class ClienteDelDocumento {

    /** El cliente tal como queda en el documento: el id (nulo si no se eligió uno) y el nombre a copiar. */
    public record Elegido(Long id, String nombre) {}

    private final ClientesParaVenta clientes;

    public ClienteDelDocumento(ClientesParaVenta clientes) {
        this.clientes = clientes;
    }

    public Elegido resolver(Long clienteId, String nombreLibre) {
        if (clienteId == null) {
            return new Elegido(null, nombreLibre);
        }
        return elegido(clientes.buscar(clienteId));
    }

    /**
     * Lo mismo para una VENTA: con {@code clienteId} TOMA la fila del cliente (lock de escritura hasta el fin de la transacción) y
     * la lee con una lectura actual. Toda venta con cliente pasa por acá, en el mismo lugar del orden de locks (después del
     * presupuesto y el remito, antes del numerador), cobre o no a cuenta corriente: InnoDB toma un lock compartido sobre el
     * cliente al insertar el comprobante, y sin esta posición común habría un ciclo con las ventas a cuenta. Sin
     * {@code clienteId} es Consumidor Final y no toca el puerto. Exige una transacción abierta (la del que llama).
     */
    public Elegido resolverParaVenta(Long clienteId) {
        if (clienteId == null) {
            return new Elegido(null, null);
        }
        return elegido(clientes.tomarParaVenta(clienteId));
    }

    /**
     * El cliente de una COBRANZA (CC-034, CC-041): lo TOMA igual que una venta (mismo primer paso del orden de locks, después
     * de la caja y antes de cualquier numerador o lectura plana). Sirve cualquier cliente ACTIVO de la ferretería: no se
     * exige saldo, deuda, pendientes ni cuenta habilitada (un cobro sin imputaciones es un adelanto, queda a favor). Ajeno o
     * inexistente es 404; desactivado, 422. Exige la transacción abierta de quien llama.
     */
    public Elegido resolverParaCobranza(Long clienteId) {
        if (clienteId == null) {
            throw new IllegalArgumentException("Una cobranza es siempre de un cliente.");
        }
        return elegido(clientes.tomarParaVenta(clienteId));
    }

    /**
     * El cliente de una ANULACIÓN de cobranza (CC-059, CC-066): lo TOMA igual que una venta o una cobranza (mismo lugar del orden de
     * locks: después de la caja, antes de cualquier lectura plana). Ajeno o inexistente es 404; desactivado, 422 «El cliente está
     * desactivado.» (así un cliente inactivo nunca queda con saldo ≠ 0: hay que reactivarlo antes de anular). NO exige saldo,
     * cuenta habilitada ni límite (CC-065). Exige la transacción abierta de quien llama.
     */
    public Elegido resolverParaAnulacion(Long clienteId) {
        if (clienteId == null) {
            throw new IllegalArgumentException("Una anulación es siempre de un cliente.");
        }
        return elegido(clientes.tomarParaVenta(clienteId));
    }

    private static Elegido elegido(Optional<ClienteReferencia> referencia) {
        ClienteReferencia cliente = referencia.orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
        if (!cliente.activo()) {
            throw new ReglaDeNegocioException("El cliente está desactivado.");
        }
        return new Elegido(cliente.id(), cliente.razonSocial());
    }
}
