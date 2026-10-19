package com.ferreteria.clientes.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Las partidas de la cuenta de un cliente (CC-030, CC-031, CC-032, CC-051): el pendiente de cada comprobante es
 * {@code Σ debe de sus VENTA − Σ de sus imputaciones} (derivado, nunca guardado) y se compara SIEMPRE con {@code compareTo} y
 * escala 2, nunca con {@code equals}. Dominio puro: recibe los débitos ya agrupados por comprobante y el imputado ya sumado;
 * no sabe de bases, de locks ni de cuál lectura los trajo (eso es del servicio, que los lee bajo el lock del cliente).
 *
 * <p>El orden FIFO es el del id de la primera partida del comprobante, estable e independiente del importe. El pendiente no
 * se recorta en cero: si la base tuviera más imputado que debido, saldría negativo y la invariante I2a lo denunciaría.
 */
public final class PartidasAbiertas {

    private static final int ESCALA = 2;

    /** Un comprobante con débito en la cuenta: lo que debe en total (Σ de sus pagos a cuenta corriente). */
    public record Debito(Long comprobanteId, Long primerMovimientoId, String referencia, LocalDateTime fecha, BigDecimal debe) {}

    /** El estado de un comprobante: lo que debe, lo imputado y lo que queda. */
    public record Partida(Long comprobanteId, String referencia, LocalDateTime fecha, BigDecimal importe,
            BigDecimal imputado, BigDecimal pendiente) {}

    /** Todas las partidas, en orden FIFO; incluye las saldadas. */
    private final List<Partida> partidas;

    private final Map<Long, Partida> porComprobante;

    public PartidasAbiertas(List<Debito> debitos, Map<Long, BigDecimal> imputadoPorComprobante) {
        if (debitos == null || imputadoPorComprobante == null) {
            throw new IllegalArgumentException("Hacen falta los débitos y el imputado de la cuenta.");
        }
        Set<Long> vistos = new HashSet<>();
        for (Debito debito : debitos) {
            if (!vistos.add(debito.comprobanteId())) {
                throw new IllegalArgumentException("El comprobante " + debito.comprobanteId() + " viene dos veces en los débitos.");
            }
        }
        List<Debito> ordenados = new ArrayList<>(debitos);
        ordenados.sort(Comparator.comparing(Debito::primerMovimientoId));
        List<Partida> lista = new ArrayList<>();
        Map<Long, Partida> indice = new LinkedHashMap<>();
        for (Debito debito : ordenados) {
            BigDecimal importe = debito.debe().setScale(ESCALA);
            BigDecimal imputado = imputadoPorComprobante.getOrDefault(debito.comprobanteId(), BigDecimal.ZERO)
                    .setScale(ESCALA);
            Partida partida = new Partida(debito.comprobanteId(), debito.referencia(), debito.fecha(), importe, imputado,
                    importe.subtract(imputado));
            lista.add(partida);
            indice.put(debito.comprobanteId(), partida);
        }
        this.partidas = List.copyOf(lista);
        this.porComprobante = indice;
    }

    /** Las partidas con pendiente mayor que cero, del comprobante más viejo al más nuevo. */
    public List<Partida> abiertas() {
        return partidas.stream().filter(p -> p.pendiente().signum() > 0).toList();
    }

    /** El pendiente del comprobante (0 si está saldado); vacío si no tiene débito en esta cuenta. */
    public Optional<BigDecimal> pendienteDe(Long comprobanteId) {
        return Optional.ofNullable(porComprobante.get(comprobanteId)).map(Partida::pendiente);
    }

    /**
     * Los comprobantes cuyo pendiente no alcanza para lo que se quiere imputar, con su deuda ACTUAL, ordenados por id: todos,
     * no solo el primero. Todos los comprobantes pedidos tienen que tener débito en la cuenta (el servicio ya resolvió el
     * 404 antes); si no, es un error de programación.
     */
    public List<Partida> excesos(Map<Long, BigDecimal> pedido) {
        List<Partida> excedidos = new ArrayList<>();
        pedido.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entrada -> {
            BigDecimal importe = entrada.getValue();
            if (importe == null || importe.signum() <= 0) {
                throw new IllegalArgumentException("El importe a imputar al comprobante " + entrada.getKey()
                        + " tiene que ser mayor que cero.");
            }
            Partida partida = porComprobante.get(entrada.getKey());
            if (partida == null) {
                throw new IllegalArgumentException("El comprobante " + entrada.getKey() + " no tiene débito en la cuenta.");
            }
            if (importe.compareTo(partida.pendiente()) > 0) {
                excedidos.add(partida);
            }
        });
        return excedidos;
    }

    /** Σ de los pendientes de las abiertas. */
    public BigDecimal totalPendiente() {
        return abiertas().stream().map(Partida::pendiente).reduce(BigDecimal.ZERO.setScale(ESCALA), BigDecimal::add);
    }
}
