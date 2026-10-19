package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CC-020, CC-021: desactivar un cliente con saldo (a favor o en contra) se rechaza; reactivar es libre. */
@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {

    private static final String MENSAJE = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";

    @Mock
    ClienteRepository repositorio;

    @InjectMocks
    ClienteService servicio;

    private static Cliente cliente(boolean activo) {
        Cliente c = Cliente.de(1L, "Cliente", null, null, CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
        c.cambiarActivo(activo);
        return c;
    }

    @Test
    @DisplayName("desactivar con saldo en contra: toma la cuenta y rechaza con 422")
    void desactivarConDeuda() {
        when(repositorio.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "250.00")));

        assertThatThrownBy(() -> servicio.cambiarActivo(5L, false))
                .isInstanceOf(ReglaDeNegocioException.class).hasMessage(MENSAJE);

        verify(repositorio, never()).findScoped(5L);
    }

    @Test
    @DisplayName("desactivar con saldo a favor (−50) también se rechaza")
    void desactivarConSaldoAFavor() {
        when(repositorio.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "-50.00")));

        assertThatThrownBy(() -> servicio.cambiarActivo(5L, false))
                .isInstanceOf(ReglaDeNegocioException.class).hasMessage(MENSAJE);
    }

    @Test
    @DisplayName("desactivar con saldo 0 (aunque tenga cuenta corriente habilitada) desactiva, bajo el lock de la cuenta")
    void desactivarConSaldoCero() {
        Cliente c = cliente(true);
        when(repositorio.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(repositorio.findScoped(5L)).thenReturn(Optional.of(c));

        Cliente resultado = servicio.cambiarActivo(5L, false);

        assertThat(resultado.isActivo()).isFalse();
        var orden = inOrder(repositorio);
        orden.verify(repositorio).bloquearCuentaScoped(5L);
        orden.verify(repositorio).findScoped(5L);
    }

    @Test
    @DisplayName("el saldo 0 con otra escala (0.000) también cuenta como cero")
    void saldoCeroConOtraEscala() {
        Cliente c = cliente(true);
        when(repositorio.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "0.000")));
        when(repositorio.findScoped(5L)).thenReturn(Optional.of(c));

        assertThat(servicio.cambiarActivo(5L, false).isActivo()).isFalse();
    }

    @Test
    @DisplayName("reactivar no chequea ni bloquea la cuenta, aunque el saldo no sea cero")
    void reactivarEsLibre() {
        Cliente c = cliente(false);
        when(repositorio.findScoped(5L)).thenReturn(Optional.of(c));

        assertThat(servicio.cambiarActivo(5L, true).isActivo()).isTrue();

        verify(repositorio, never()).bloquearCuentaScoped(5L);
    }

    @Test
    @DisplayName("un cliente inexistente o ajeno es 404 ANTES que el 422, al desactivar y al reactivar")
    void inexistenteEs404() {
        when(repositorio.bloquearCuentaScoped(99L)).thenReturn(Optional.empty());
        when(repositorio.findScoped(98L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarActivo(99L, false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> servicio.cambiarActivo(98L, true)).isInstanceOf(ResourceNotFoundException.class);
    }
}
