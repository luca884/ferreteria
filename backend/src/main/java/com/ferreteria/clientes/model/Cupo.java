package com.ferreteria.clientes.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * La regla del límite de cuenta corriente (CC-003, CC-004), dominio puro. Límite NULL = cuenta corriente no habilitada; 0 =
 * habilitada sin crédito; no hay "sin tope". El saldo es positivo si el cliente debe y negativo si tiene saldo a favor.
 * Una venta a cuenta "excede" si saldo + importe > límite; igual al límite NO excede. Compara por valor (compareTo), nunca
 * por escala. Quién puede autorizar el exceso no es de este módulo: es política de ventas.
 */
public record Cupo(BigDecimal limite, BigDecimal saldo, BigDecimal importe) {

    public Cupo {
        Objects.requireNonNull(saldo, "saldo");
        Objects.requireNonNull(importe, "importe");
        if (importe.signum() < 0) {
            throw new IllegalArgumentException("El importe no puede ser negativo.");
        }
    }

    public static Cupo de(BigDecimal limite, BigDecimal saldo, BigDecimal importe) {
        return new Cupo(limite, saldo, importe);
    }

    public boolean habilitada() {
        return limite != null;
    }

    /** Límite - saldo (puede superar el límite con saldo a favor, o ser negativo si el límite bajó); null si no está habilitada. */
    public BigDecimal disponible() {
        return habilitada() ? limite.subtract(saldo) : null;
    }

    /** Cuánto se pasa del límite: saldo + importe - límite si excede; 0 si alcanza o no está habilitada. */
    public BigDecimal exceso() {
        if (!habilitada()) {
            return BigDecimal.ZERO;
        }
        BigDecimal pasado = saldo.add(importe).subtract(limite);
        return pasado.signum() > 0 ? pasado : BigDecimal.ZERO;
    }

    /** Solo tiene sentido con la cuenta habilitada; sin habilitar, mirar {@link #habilitada()} primero. */
    public boolean excede() {
        return exceso().signum() > 0;
    }

    /** Está habilitada y la venta entra en el límite. */
    public boolean alcanza() {
        return habilitada() && !excede();
    }
}
