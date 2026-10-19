package com.ferreteria.clientes;

import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import java.math.BigDecimal;

/** La proyección de la cuenta bloqueada para los tests con Mockito (la real la arma Spring Data con la consulta nativa). */
public record CuentaFalsa(String razonSocial, BigDecimal saldo, BigDecimal limite, boolean activo)
        implements CuentaBloqueada {

    public static CuentaFalsa de(String limite, String saldo) {
        return new CuentaFalsa("Cliente de prueba", new BigDecimal(saldo), limite == null ? null : new BigDecimal(limite),
                true);
    }

    @Override
    public String getRazonSocial() {
        return razonSocial;
    }

    @Override
    public BigDecimal getSaldo() {
        return saldo;
    }

    @Override
    public BigDecimal getLimiteCuentaCorriente() {
        return limite;
    }

    @Override
    public boolean getActivo() {
        return activo;
    }
}
