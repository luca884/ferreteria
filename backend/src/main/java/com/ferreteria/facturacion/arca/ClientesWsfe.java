package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.model.Ambiente;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * El {@link ClienteWsfe} de cada ambiente del cableado real (rama 6, S6), para quien necesita lo que no está en el puerto
 * {@code ServicioWsfe} del motor (L4): {@code FEDummy} y {@code FEParamGetPtosVenta}, de «Probar conexión» (C1) y del
 * chequeo del super admin (C2). El motor los ve por {@code WsfePorAmbiente}, armado con estos mismos clientes.
 */
public class ClientesWsfe {

    private final Map<Ambiente, ClienteWsfe> porAmbiente = new EnumMap<>(Ambiente.class);

    /** @param clientes uno por ambiente, como mucho */
    public ClientesWsfe(Collection<ClienteWsfe> clientes) {
        for (ClienteWsfe cliente : clientes) {
            Objects.requireNonNull(cliente, "el cliente de WSFEv1");
            if (porAmbiente.put(cliente.ambiente(), cliente) != null) {
                throw new IllegalArgumentException("Dos clientes de WSFEv1 para " + cliente.ambiente() + ".");
            }
        }
    }

    /**
     * El cliente de ese ambiente.
     *
     * @throws IllegalStateException si el ambiente no tiene cliente
     */
    public ClienteWsfe de(Ambiente ambiente) {
        ClienteWsfe cliente = porAmbiente.get(Objects.requireNonNull(ambiente, "el ambiente"));
        if (cliente == null) {
            throw new IllegalStateException("No hay un cliente de WSFEv1 para " + ambiente + ".");
        }
        return cliente;
    }

    /** Todos, por ambiente (sin modificar). */
    public Map<Ambiente, ClienteWsfe> porAmbiente() {
        return Collections.unmodifiableMap(porAmbiente);
    }
}
