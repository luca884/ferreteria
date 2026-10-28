package com.ferreteria.clientes;

import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import java.math.BigDecimal;

/**
 * La proyección de la cuenta bloqueada para los tests con Mockito (la real la arma Spring Data con la consulta nativa). Los
 * datos fiscales van como en la base: la condición y el tipo de documento con el nombre del enum del modelo.
 */
public record CuentaFalsa(String razonSocial, BigDecimal saldo, BigDecimal limite, boolean activo, String condicionIva,
        String tipoDocumento, String numeroDocumento, String domicilio) implements CuentaBloqueada {

    /**
     * Consumidor Final sin documento ni domicilio: los tests de cuenta corriente venden por debajo del tope de Consumidor
     * Final (GDS-32).
     */
    public CuentaFalsa(String razonSocial, BigDecimal saldo, BigDecimal limite, boolean activo) {
        this(razonSocial, saldo, limite, activo, "CONSUMIDOR_FINAL", null, null, null);
    }

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

    @Override
    public String getTipoDocumento() {
        return tipoDocumento;
    }

    @Override
    public String getNumeroDocumento() {
        return numeroDocumento;
    }

    @Override
    public String getCondicionIva() {
        return condicionIva;
    }

    @Override
    public String getDomicilio() {
        return domicilio;
    }
}
