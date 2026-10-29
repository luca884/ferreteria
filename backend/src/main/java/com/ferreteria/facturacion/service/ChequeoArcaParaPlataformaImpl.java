package com.ferreteria.facturacion.service;

import com.ferreteria.facturacion.api.ChequeoArcaParaPlataforma;
import com.ferreteria.facturacion.arca.ClientesWsfe;
import com.ferreteria.facturacion.arca.FeDummyResponse;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * El chequeo FEDummy del super admin (rama 6, C2, S9): el {@code ClienteWsfe} real de homologación y el de producción, con su
 * transporte HTTP (TLS y timeouts de C6). Sin ticket ni ferretería: {@code FEDummy} no lleva {@code Auth}. Sin transacción:
 * es solo una llamada a ARCA. Los milisegundos salen de un intervalo ({@code System.nanoTime}), no del reloj del sistema.
 */
@Service
public class ChequeoArcaParaPlataformaImpl implements ChequeoArcaParaPlataforma {

    static final String SERVIDORES_CAIDOS = "ARCA respondió, pero no todos sus servidores están OK.";

    private static final Logger log = LoggerFactory.getLogger(ChequeoArcaParaPlataformaImpl.class);

    private final ClientesWsfe clientes;
    private final LongSupplier nanos;

    @Autowired
    public ChequeoArcaParaPlataformaImpl(ClientesWsfe clientes) {
        this(clientes, System::nanoTime);
    }

    /** Con otro contador de nanosegundos (en los tests). */
    ChequeoArcaParaPlataformaImpl(ClientesWsfe clientes, LongSupplier nanos) {
        this.clientes = clientes;
        this.nanos = nanos;
    }

    @Override
    public ChequeoFeDummy feDummy() {
        return new ChequeoFeDummy(feDummy(Ambiente.HOMOLOGACION), feDummy(Ambiente.PRODUCCION));
    }

    private ResultadoFeDummy feDummy(Ambiente ambiente) {
        long desde = nanos.getAsLong();
        try {
            FeDummyResponse dummy = clientes.de(ambiente).feDummy();
            long milisegundos = (nanos.getAsLong() - desde) / 1_000_000;
            boolean ok = dummy.todoOk();
            log.info("FEDummy del super admin en {}: {} en {} ms", ambiente, ok ? "OK" : "con servidores caídos",
                    milisegundos);
            return new ResultadoFeDummy(ok, dummy.appServer(), dummy.dbServer(), dummy.authServer(), milisegundos,
                    ok ? null : SERVIDORES_CAIDOS);
        } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
            log.warn("FEDummy del super admin en {}: {}", ambiente, e.getMessage());
            return new ResultadoFeDummy(false, null, null, null, null, e.getMessage());
        }
    }
}
