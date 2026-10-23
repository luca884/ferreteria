package com.ferreteria.clientes.model;

import com.ferreteria.clientes.model.Imputacion.Origen;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * El crédito disponible de un cliente (GDS-20, G59): lo que cada recibo y cada Descuento tiene todavía sin imputar («a
 * cuenta»), y cómo lo consume una deuda nueva. Una venta a cuenta corriente o un Recargo se pagan solos con ese crédito:
 * FIFO por el id del movimiento del libro que lo originó (el más viejo primero), solo lo que hay y solo hasta la deuda. Dominio
 * puro: recibe los créditos ya sumados (el neto de un recibo es su COBRO menos su ANULACION) y lo imputado vigente de cada uno;
 * no sabe de bases ni de locks.
 */
public final class CreditoDisponible {

    private static final int ESCALA = 2;

    /** El crédito de un origen: su importe neto y lo ya imputado de él (vigente). */
    public record Credito(Origen origen, Long movimientoId, BigDecimal importe, BigDecimal imputado) {

        public Credito {
            if (origen == null || movimientoId == null || importe == null || imputado == null) {
                throw new IllegalArgumentException("El crédito necesita su origen, su movimiento y sus importes.");
            }
        }

        BigDecimal disponible() {
            return importe.subtract(imputado).setScale(ESCALA);
        }
    }

    /** Cuánto de la deuda se paga con el crédito de ese origen. */
    public record Aplicacion(Origen origen, BigDecimal importe) {}

    private final List<Credito> creditos;

    public CreditoDisponible(List<Credito> creditos) {
        if (creditos == null) {
            throw new IllegalArgumentException("Hacen falta los créditos de la cuenta.");
        }
        this.creditos = creditos.stream().sorted(Comparator.comparing(Credito::movimientoId)).toList();
    }

    /** Σ de lo que queda sin imputar (los agotados no suman). */
    public BigDecimal total() {
        return creditos.stream().map(Credito::disponible).filter(d -> d.signum() > 0)
                .reduce(BigDecimal.ZERO.setScale(ESCALA), BigDecimal::add);
    }

    /** Qué créditos pagan una deuda de {@code deuda}: del más viejo al más nuevo, hasta cubrirla o agotar el crédito. */
    public List<Aplicacion> aplicar(BigDecimal deuda) {
        if (deuda == null || deuda.signum() < 0) {
            throw new IllegalArgumentException("La deuda a pagar con crédito no puede ser nula ni negativa.");
        }
        List<Aplicacion> aplicadas = new ArrayList<>();
        BigDecimal falta = deuda.setScale(ESCALA);
        for (Credito credito : creditos) {
            BigDecimal disponible = credito.disponible();
            if (falta.signum() == 0) {
                break;
            }
            if (disponible.signum() <= 0) {
                continue;
            }
            BigDecimal tomado = disponible.min(falta);
            aplicadas.add(new Aplicacion(credito.origen(), tomado));
            falta = falta.subtract(tomado);
        }
        return aplicadas;
    }
}
