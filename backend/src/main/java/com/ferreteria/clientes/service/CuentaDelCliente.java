package com.ferreteria.clientes.service;

import java.math.BigDecimal;

/**
 * La cuenta corriente de un cliente tal como la ve quien la consulta (CC-007). {@code alcanza} solo viene si se consultó con
 * un importe. {@code limite}, {@code saldo} y {@code disponible} son las cifras: solo las trae la vista del Dueño (el
 * Vendedor las recibe en null); {@code limite} y {@code disponible} además son null si la cuenta no está habilitada.
 */
public record CuentaDelCliente(boolean habilitada, Boolean alcanza, BigDecimal limite, BigDecimal saldo,
        BigDecimal disponible) {}
