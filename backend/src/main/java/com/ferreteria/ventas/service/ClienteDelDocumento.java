package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * El cliente que lleva una venta, un presupuesto o un remito (CL-004). Con {@code clienteId} el cliente tiene que ser
 * uno activo de la ferretería: el nombre del documento sale de su razón social, y el texto libre se ignora. Sin
 * {@code clienteId} vale el texto libre (o nada: Consumidor Final). El documento guarda el id y una copia del nombre.
 */
@Component
public class ClienteDelDocumento {

    /**
     * El cliente tal como queda en el documento: el id (nulo si no se eligió uno), el nombre a copiar y el receptor de la
     * factura (CU-07, D4), con la condición frente al IVA, el documento y el domicilio leídos junto con el cliente. Sin
     * cliente elegido (Consumidor Final o un nombre libre) el receptor es {@link ReceptorVenta#sinCliente()}.
     */
    public record Elegido(Long id, String nombre, ReceptorVenta receptor) {

        public Elegido {
            Objects.requireNonNull(receptor, "Falta el receptor del documento.");
        }

        /** Tiene documento (DNI, CUIT o CUIL); Consumidor Final o un nombre libre nunca lo está (GDS-32). */
        public boolean identificado() {
            return receptor.identificado();
        }
    }

    private final ClientesParaVenta clientes;

    public ClienteDelDocumento(ClientesParaVenta clientes) {
        this.clientes = clientes;
    }

    public Elegido resolver(Long clienteId, String nombreLibre) {
        if (clienteId == null) {
            return new Elegido(null, nombreLibre, ReceptorVenta.sinCliente());
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
            return new Elegido(null, null, ReceptorVenta.sinCliente());
        }
        return elegido(clientes.tomarParaVenta(clienteId));
    }

    /**
     * El cliente de una venta ya hecha, para «Reintentar» su factura (cu-07-contingencias C2, D10): lo TOMA igual que una
     * venta (lock de escritura y lectura actual), así la factura nueva lleva el cliente de hoy. Sirve aunque esté desactivado:
     * la venta ya se hizo y la factura es suya. Sin {@code clienteId} es Consumidor Final, como al vender. Uno que ya no
     * aparece es 404. Exige la transacción abierta de quien llama.
     */
    public Elegido resolverParaReintento(Long clienteId) {
        if (clienteId == null) {
            return new Elegido(null, null, ReceptorVenta.sinCliente());
        }
        ClienteReferencia cliente = clientes.tomarParaVenta(clienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
        return new Elegido(cliente.id(), cliente.razonSocial(), receptor(cliente));
    }

    /**
     * El cliente de una venta que se anula (CU-08): lo TOMA igual que una venta, en el mismo lugar del orden de locks
     * (antes del comprobante y de cualquier numerador), aunque esté desactivado: la venta ya se hizo y anularla no le cambia
     * nada al cliente. Mismo lock que {@link #resolverParaReintento}. Exige la transacción abierta de quien llama.
     */
    public void tomarParaAnularVenta(Long clienteId) {
        resolverParaReintento(clienteId);
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
        return new Elegido(cliente.id(), cliente.razonSocial(), receptor(cliente));
    }

    /**
     * El cliente como receptor de la factura: su condición frente al IVA, su documento, su razón social y su domicilio, tal
     * como se leyeron (bajo el lock en la venta). Si están completos lo decide facturación (D4), no acá.
     */
    private static ReceptorVenta receptor(ClienteReferencia cliente) {
        return new ReceptorVenta(condicion(cliente.condicionIva()),
                cliente.tipoDocumento() == null ? null : tipoDocumento(cliente.tipoDocumento()), cliente.numeroDocumento(),
                cliente.razonSocial(), cliente.domicilio());
    }

    private static FacturacionParaVenta.CondicionIva condicion(ClienteReferencia.CondicionIva condicion) {
        return switch (condicion) {
            case CONSUMIDOR_FINAL -> FacturacionParaVenta.CondicionIva.CONSUMIDOR_FINAL;
            case RESPONSABLE_INSCRIPTO -> FacturacionParaVenta.CondicionIva.RESPONSABLE_INSCRIPTO;
            case MONOTRIBUTISTA -> FacturacionParaVenta.CondicionIva.MONOTRIBUTISTA;
            case EXENTO -> FacturacionParaVenta.CondicionIva.EXENTO;
        };
    }

    private static FacturacionParaVenta.TipoDocumento tipoDocumento(ClienteReferencia.TipoDocumento tipo) {
        return switch (tipo) {
            case DNI -> FacturacionParaVenta.TipoDocumento.DNI;
            case CUIT -> FacturacionParaVenta.TipoDocumento.CUIT;
            case CUIL -> FacturacionParaVenta.TipoDocumento.CUIL;
        };
    }
}
