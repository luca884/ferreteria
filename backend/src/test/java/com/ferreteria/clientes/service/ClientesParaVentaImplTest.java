package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CL-004: lo que `ventas` puede saber de un cliente: quién es y si está activo, nada más. */
@ExtendWith(MockitoExtension.class)
class ClientesParaVentaImplTest {

    @Mock
    ClienteRepository repositorio;

    @InjectMocks
    ClientesParaVentaImpl puerto;

    private static Cliente cliente(String razonSocial, boolean activo) {
        Cliente c = Cliente.de(1L, razonSocial, null, null, CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
        c.cambiarActivo(activo);
        return c;
    }

    @Test
    @DisplayName("un cliente activo de la ferretería vuelve con su id, su razón social y activo")
    void clienteActivo() {
        Cliente c = cliente("Ferretería Norte", true);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 7L);
        when(repositorio.findScoped(7L)).thenReturn(Optional.of(c));

        Optional<ClienteReferencia> referencia = puerto.buscar(7L);

        assertThat(referencia).contains(new ClienteReferencia(7L, "Ferretería Norte", true));
    }

    @Test
    @DisplayName("un cliente desactivado también vuelve, marcado inactivo: ventas decide qué hacer")
    void clienteInactivo() {
        Cliente c = cliente("Viejo", false);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 8L);
        when(repositorio.findScoped(8L)).thenReturn(Optional.of(c));

        assertThat(puerto.buscar(8L)).contains(new ClienteReferencia(8L, "Viejo", false));
    }

    @Test
    @DisplayName("el que no existe o es de otra ferretería no vuelve")
    void noExiste() {
        when(repositorio.findScoped(9L)).thenReturn(Optional.empty());

        assertThat(puerto.buscar(9L)).isEmpty();
    }

    // ---- tomarParaVenta: el lock fresco del cliente (la regla MANDATORY se prueba contra MySQL, sin proxy no aplica) ----

    @Test
    @DisplayName("tomarParaVenta devuelve id, razón social y activo de lo que leyó bajo lock, sin cargar la entidad")
    void tomarParaVenta() {
        when(repositorio.bloquearCuentaScoped(7L)).thenReturn(Optional.of(
                new CuentaFalsa("Ferretería Norte", BigDecimal.ZERO, null, true)));

        assertThat(puerto.tomarParaVenta(7L)).contains(new ClienteReferencia(7L, "Ferretería Norte", true));
        org.mockito.Mockito.verify(repositorio, org.mockito.Mockito.never()).findScoped(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("tomarParaVenta de un cliente desactivado lo marca inactivo (el estado es el del lock, no una foto vieja)")
    void tomarParaVentaInactivo() {
        when(repositorio.bloquearCuentaScoped(8L)).thenReturn(Optional.of(
                new CuentaFalsa("Viejo", new BigDecimal("10.00"), new BigDecimal("500.00"), false)));

        assertThat(puerto.tomarParaVenta(8L)).contains(new ClienteReferencia(8L, "Viejo", false));
    }

    @Test
    @DisplayName("tomarParaVenta de uno que no existe o es de otra ferretería no vuelve")
    void tomarParaVentaNoExiste() {
        when(repositorio.bloquearCuentaScoped(9L)).thenReturn(Optional.empty());

        assertThat(puerto.tomarParaVenta(9L)).isEmpty();
    }
}
