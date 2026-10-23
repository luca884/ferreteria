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
 * Las partidas de la cuenta de un cliente (CC-030, CC-031, CC-032, CC-051, GDS-20): el pendiente de cada comprobante es
 * {@code Σ debe de sus VENTA − Σ de sus imputaciones}, y el de cada Recargo es {@code su importe − Σ de sus imputaciones}
 * (derivado, nunca guardado). Se compara SIEMPRE con {@code compareTo} y escala 2, nunca con {@code equals}. Dominio puro:
 * recibe los débitos ya agrupados y el imputado ya sumado; no sabe de bases, de locks ni de cuál lectura los trajo (eso es
 * del servicio, que los lee bajo el lock del cliente).
 *
 * <p>El orden FIFO es el del id de la primera partida del libro, estable e independiente del importe, y mezcla comprobantes y
 * Recargos. El pendiente no se recorta en cero: si la base tuviera más imputado que debido, saldría negativo y la invariante
 * I2a lo denunciaría. Los ids de comprobante y de Recargo son espacios distintos (el comprobante 5 y el Recargo 5 conviven).
 */
public final class PartidasAbiertas {

    private static final int ESCALA = 2;

    /**
     * Una deuda con débito en la cuenta: lo que debe en total (Σ de los pagos a cuenta corriente de un comprobante, o el
     * importe de un Recargo). Exactamente uno de {@code comprobanteId} y {@code ajusteId}.
     */
    public record Debito(Long comprobanteId, Long ajusteId, Long primerMovimientoId, String referencia, LocalDateTime fecha,
            BigDecimal debe) {

        /** Un comprobante (la forma de antes de los Recargos). */
        public Debito(Long comprobanteId, Long primerMovimientoId, String referencia, LocalDateTime fecha, BigDecimal debe) {
            this(comprobanteId, null, primerMovimientoId, referencia, fecha, debe);
        }

        public static Debito deRecargo(Long ajusteId, Long movimientoId, String referencia, LocalDateTime fecha, BigDecimal debe) {
            return new Debito(null, ajusteId, movimientoId, referencia, fecha, debe);
        }
    }

    /** El estado de una deuda: lo que debe, lo imputado y lo que queda. Exactamente uno de {@code comprobanteId} y {@code ajusteId}. */
    public record Partida(Long comprobanteId, Long ajusteId, String referencia, LocalDateTime fecha, BigDecimal importe,
            BigDecimal imputado, BigDecimal pendiente) {

        /** Un comprobante (la forma de antes de los Recargos). */
        public Partida(Long comprobanteId, String referencia, LocalDateTime fecha, BigDecimal importe, BigDecimal imputado,
                BigDecimal pendiente) {
            this(comprobanteId, null, referencia, fecha, importe, imputado, pendiente);
        }
    }

    /** Todas las partidas, en orden FIFO; incluye las saldadas. */
    private final List<Partida> partidas;

    private final Map<Long, Partida> porComprobante;
    private final Map<Long, Partida> porAjuste;

    public PartidasAbiertas(List<Debito> debitos, Map<Long, BigDecimal> imputadoPorComprobante) {
        this(debitos, imputadoPorComprobante, Map.of());
    }

    public PartidasAbiertas(List<Debito> debitos, Map<Long, BigDecimal> imputadoPorComprobante,
            Map<Long, BigDecimal> imputadoPorAjuste) {
        if (debitos == null || imputadoPorComprobante == null || imputadoPorAjuste == null) {
            throw new IllegalArgumentException("Hacen falta los débitos y el imputado de la cuenta.");
        }
        Set<Long> comprobantesVistos = new HashSet<>();
        Set<Long> ajustesVistos = new HashSet<>();
        for (Debito debito : debitos) {
            if ((debito.comprobanteId() == null) == (debito.ajusteId() == null)) {
                throw new IllegalArgumentException("Un débito es de exactamente un comprobante o un Recargo.");
            }
            if (debito.comprobanteId() != null && !comprobantesVistos.add(debito.comprobanteId())) {
                throw new IllegalArgumentException("El comprobante " + debito.comprobanteId() + " viene dos veces en los débitos.");
            }
            if (debito.ajusteId() != null && !ajustesVistos.add(debito.ajusteId())) {
                throw new IllegalArgumentException("El Recargo " + debito.ajusteId() + " viene dos veces en los débitos.");
            }
        }
        List<Debito> ordenados = new ArrayList<>(debitos);
        ordenados.sort(Comparator.comparing(Debito::primerMovimientoId));
        List<Partida> lista = new ArrayList<>();
        Map<Long, Partida> indiceComprobantes = new LinkedHashMap<>();
        Map<Long, Partida> indiceAjustes = new LinkedHashMap<>();
        for (Debito debito : ordenados) {
            BigDecimal importe = debito.debe().setScale(ESCALA);
            Map<Long, BigDecimal> imputados = debito.comprobanteId() != null ? imputadoPorComprobante : imputadoPorAjuste;
            Long id = debito.comprobanteId() != null ? debito.comprobanteId() : debito.ajusteId();
            BigDecimal imputado = imputados.getOrDefault(id, BigDecimal.ZERO).setScale(ESCALA);
            Partida partida = new Partida(debito.comprobanteId(), debito.ajusteId(), debito.referencia(), debito.fecha(), importe,
                    imputado, importe.subtract(imputado));
            lista.add(partida);
            (debito.comprobanteId() != null ? indiceComprobantes : indiceAjustes).put(id, partida);
        }
        this.partidas = List.copyOf(lista);
        this.porComprobante = indiceComprobantes;
        this.porAjuste = indiceAjustes;
    }

    /** Las partidas con pendiente mayor que cero, de la más vieja a la más nueva. */
    public List<Partida> abiertas() {
        return partidas.stream().filter(p -> p.pendiente().signum() > 0).toList();
    }

    /** El pendiente del comprobante (0 si está saldado); vacío si no tiene débito en esta cuenta. */
    public Optional<BigDecimal> pendienteDe(Long comprobanteId) {
        return Optional.ofNullable(porComprobante.get(comprobanteId)).map(Partida::pendiente);
    }

    /** El pendiente del Recargo (0 si está saldado); vacío si no tiene débito en esta cuenta. */
    public Optional<BigDecimal> pendienteDeAjuste(Long ajusteId) {
        return Optional.ofNullable(porAjuste.get(ajusteId)).map(Partida::pendiente);
    }

    /**
     * Los comprobantes cuyo pendiente no alcanza para lo que se quiere imputar, con su deuda ACTUAL, ordenados por id: todos,
     * no solo el primero. Todos los comprobantes pedidos tienen que tener débito en la cuenta (el servicio ya resolvió el
     * 404 antes); si no, es un error de programación.
     */
    public List<Partida> excesos(Map<Long, BigDecimal> pedido) {
        return excesos(pedido, Map.of());
    }

    /**
     * Lo mismo con Recargos: primero los comprobantes excedidos (por id) y después los Recargos excedidos (por id).
     */
    public List<Partida> excesos(Map<Long, BigDecimal> pedidoComprobantes, Map<Long, BigDecimal> pedidoRecargos) {
        List<Partida> excedidos = new ArrayList<>();
        excedidos.addAll(excesosDe(pedidoComprobantes, porComprobante, "comprobante"));
        excedidos.addAll(excesosDe(pedidoRecargos, porAjuste, "Recargo"));
        return excedidos;
    }

    private static List<Partida> excesosDe(Map<Long, BigDecimal> pedido, Map<Long, Partida> partidas, String que) {
        List<Partida> excedidos = new ArrayList<>();
        pedido.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entrada -> {
            BigDecimal importe = entrada.getValue();
            if (importe == null || importe.signum() <= 0) {
                throw new IllegalArgumentException("El importe a imputar al " + que + " " + entrada.getKey()
                        + " tiene que ser mayor que cero.");
            }
            Partida partida = partidas.get(entrada.getKey());
            if (partida == null) {
                throw new IllegalArgumentException("El " + que + " " + entrada.getKey() + " no tiene débito en la cuenta.");
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
