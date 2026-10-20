package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CL-004: el cliente que un documento (venta, presupuesto o remito) lleva: el elegido o el texto libre. */
@ExtendWith(MockitoExtension.class)
class ClienteDelDocumentoTest {

    @Mock
    ClientesParaVenta clientes;

    @InjectMocks
    ClienteDelDocumento resolutor;

    @Test
    @DisplayName("sin clienteId y sin texto no hay cliente: Consumidor Final, y no se consulta nada")
    void sinCliente() {
        ClienteDelDocumento.Elegido elegido = resolutor.resolver(null, null);

        assertThat(elegido.id()).isNull();
        assertThat(elegido.nombre()).isNull();
        verifyNoInteractions(clientes);
    }

    @Test
    @DisplayName("sin clienteId vale el texto libre tal cual, y no se consulta nada")
    void textoLibre() {
        ClienteDelDocumento.Elegido elegido = resolutor.resolver(null, "Juan, el del taller");

        assertThat(elegido.id()).isNull();
        assertThat(elegido.nombre()).isEqualTo("Juan, el del taller");
        verifyNoInteractions(clientes);
    }

    @Test
    @DisplayName("con clienteId el nombre sale del cliente y el texto libre se ignora")
    void gana_el_cliente() {
        when(clientes.buscar(7L)).thenReturn(Optional.of(new ClienteReferencia(7L, "Ferretería Norte SRL", true)));

        ClienteDelDocumento.Elegido elegido = resolutor.resolver(7L, "otro nombre escrito a mano");

        assertThat(elegido.id()).isEqualTo(7L);
        assertThat(elegido.nombre()).isEqualTo("Ferretería Norte SRL");
    }

    @Test
    @DisplayName("un cliente que no existe o es de otra ferretería es 404")
    void noExiste() {
        when(clientes.buscar(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolutor.resolver(9L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Cliente no encontrado");
    }

    @Test
    @DisplayName("un cliente desactivado es 422: se usa solo uno activo")
    void desactivado() {
        when(clientes.buscar(8L)).thenReturn(Optional.of(new ClienteReferencia(8L, "Viejo", false)));

        assertThatThrownBy(() -> resolutor.resolver(8L, null))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
    }

    // ---- resolverParaVenta (CC-008, CC-012): como resolver, pero TOMANDO la fila del cliente

    @Test
    @DisplayName("resolverParaVenta sin clienteId no tiene cliente y no toca el puerto")
    void paraVentaSinCliente() {
        ClienteDelDocumento.Elegido elegido = resolutor.resolverParaVenta(null);

        assertThat(elegido.id()).isNull();
        assertThat(elegido.nombre()).isNull();
        verifyNoInteractions(clientes);
    }

    @Test
    @DisplayName("resolverParaVenta toma al cliente (tomarParaVenta, no buscar) y copia su razón social")
    void paraVentaTomaAlCliente() {
        when(clientes.tomarParaVenta(7L)).thenReturn(Optional.of(new ClienteReferencia(7L, "Ferretería Norte SRL", true)));

        ClienteDelDocumento.Elegido elegido = resolutor.resolverParaVenta(7L);

        assertThat(elegido.id()).isEqualTo(7L);
        assertThat(elegido.nombre()).isEqualTo("Ferretería Norte SRL");
        verify(clientes).tomarParaVenta(7L);
        verifyNoMoreInteractions(clientes);
    }

    @Test
    @DisplayName("resolverParaVenta: ajeno o inexistente es 404 y desactivado es 422, igual que resolver")
    void paraVentaMismosRechazos() {
        when(clientes.tomarParaVenta(9L)).thenReturn(Optional.empty());
        when(clientes.tomarParaVenta(8L)).thenReturn(Optional.of(new ClienteReferencia(8L, "Viejo", false)));

        assertThatThrownBy(() -> resolutor.resolverParaVenta(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Cliente no encontrado");
        assertThatThrownBy(() -> resolutor.resolverParaVenta(8L))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
    }

    @Test
    @DisplayName("resolver (presupuesto y remito) sigue usando buscar y NUNCA toma la fila del cliente")
    void resolverPlanoNoTomaLaFila() {
        when(clientes.buscar(7L)).thenReturn(Optional.of(new ClienteReferencia(7L, "Norte", true)));

        resolutor.resolver(7L, null);

        verify(clientes).buscar(7L);
        verifyNoMoreInteractions(clientes);
    }

    // ---- resolverParaCobranza (CC-034, CC-041): el cliente de una cobranza; cualquier cliente ACTIVO, también el adelanto ----

    @Test
    @DisplayName("resolverParaCobranza toma al cliente (tomarParaVenta, no buscar) y copia su razón social")
    void paraCobranzaTomaAlCliente() {
        when(clientes.tomarParaVenta(7L)).thenReturn(Optional.of(new ClienteReferencia(7L, "Ferretería Norte SRL", true)));

        ClienteDelDocumento.Elegido elegido = resolutor.resolverParaCobranza(7L);

        assertThat(elegido.id()).isEqualTo(7L);
        assertThat(elegido.nombre()).isEqualTo("Ferretería Norte SRL");
        verify(clientes).tomarParaVenta(7L);
        verifyNoMoreInteractions(clientes);
    }

    @Test
    @DisplayName("resolverParaCobranza: ajeno o inexistente es 404 y desactivado es 422 «El cliente está desactivado.»")
    void paraCobranzaRechazos() {
        when(clientes.tomarParaVenta(9L)).thenReturn(Optional.empty());
        when(clientes.tomarParaVenta(8L)).thenReturn(Optional.of(new ClienteReferencia(8L, "Viejo", false)));

        assertThatThrownBy(() -> resolutor.resolverParaCobranza(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Cliente no encontrado");
        assertThatThrownBy(() -> resolutor.resolverParaCobranza(8L))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
    }

    @Test
    @DisplayName("resolverParaCobranza NO pide saldo, cuenta habilitada ni límite: un cliente activo (la referencia solo trae id, nombre y activo) resuelve: adelanto")
    void paraCobranzaNoExigeSaldoNiCuenta() {
        // ClienteReferencia no lleva saldo ni límite: el resolutor ni siquiera puede exigirlos.
        when(clientes.tomarParaVenta(5L)).thenReturn(Optional.of(new ClienteReferencia(5L, "Sin deuda ni cuenta", true)));

        assertThat(resolutor.resolverParaCobranza(5L).id()).isEqualTo(5L);
    }

    @Test
    @DisplayName("resolverParaCobranza sin cliente es un error de programación (la cobranza siempre es de un cliente): no toca el puerto")
    void paraCobranzaSinCliente() {
        assertThatThrownBy(() -> resolutor.resolverParaCobranza(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(clientes);
    }

    @Test
    @DisplayName("resolver y resolverParaVenta siguen igual con resolverParaCobranza presente (regresión: resolver usa buscar)")
    void regresionDeLosOtrosResolutores() {
        when(clientes.buscar(7L)).thenReturn(Optional.of(new ClienteReferencia(7L, "Norte", true)));

        resolutor.resolver(7L, null);

        verify(clientes).buscar(7L);
        verifyNoMoreInteractions(clientes);
    }
}
