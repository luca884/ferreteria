package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.repository.ClienteRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CL-004: lo que `ventas` puede saber de un cliente: quién es, si está activo y sus datos fiscales para la factura (D4),
 * nada más.
 */
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

    /** Un Consumidor Final sin documento ni domicilio, como lo devuelve el puerto. */
    private static ClienteReferencia consumidorFinal(Long id, String razonSocial, boolean activo) {
        return new ClienteReferencia(id, razonSocial, activo, ClienteReferencia.CondicionIva.CONSUMIDOR_FINAL, null, null,
                null);
    }

    @Test
    @DisplayName("un cliente activo de la ferretería vuelve con su id, su razón social y activo")
    void clienteActivo() {
        Cliente c = cliente("Ferretería Norte", true);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 7L);
        when(repositorio.findScoped(7L)).thenReturn(Optional.of(c));

        Optional<ClienteReferencia> referencia = puerto.buscar(7L);

        assertThat(referencia).contains(consumidorFinal(7L, "Ferretería Norte", true));
    }

    @Test
    @DisplayName("un cliente desactivado también vuelve, marcado inactivo: ventas decide qué hacer")
    void clienteInactivo() {
        Cliente c = cliente("Viejo", false);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 8L);
        when(repositorio.findScoped(8L)).thenReturn(Optional.of(c));

        assertThat(puerto.buscar(8L)).contains(consumidorFinal(8L, "Viejo", false));
    }

    @Test
    @DisplayName("buscar trae la condición de IVA, el documento y el domicilio del cliente (D4)")
    void buscarTraeLosDatosFiscales() {
        Cliente c = Cliente.de(1L, "Ferretería Norte SRL", TipoDocumento.CUIT, "30712345671",
                CondicionIvaCliente.RESPONSABLE_INSCRIPTO, "Av. Colón 1234", null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 7L);
        when(repositorio.findScoped(7L)).thenReturn(Optional.of(c));

        ClienteReferencia referencia = puerto.buscar(7L).orElseThrow();

        assertThat(referencia).isEqualTo(new ClienteReferencia(7L, "Ferretería Norte SRL", true,
                ClienteReferencia.CondicionIva.RESPONSABLE_INSCRIPTO, ClienteReferencia.TipoDocumento.CUIT, "30712345671",
                "Av. Colón 1234"));
        assertThat(referencia.identificado()).isTrue();
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

        assertThat(puerto.tomarParaVenta(7L)).contains(consumidorFinal(7L, "Ferretería Norte", true));
        org.mockito.Mockito.verify(repositorio, org.mockito.Mockito.never()).findScoped(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("tomarParaVenta trae la condición de IVA, el documento y el domicilio de la misma lectura bajo lock (D4)")
    void tomarParaVentaTraeLosDatosFiscales() {
        when(repositorio.bloquearCuentaScoped(7L)).thenReturn(Optional.of(new CuentaFalsa("Juan Pérez", BigDecimal.ZERO,
                null, true, "MONOTRIBUTISTA", "CUIT", "20123456786", "Av. Luro 3200")));

        ClienteReferencia referencia = puerto.tomarParaVenta(7L).orElseThrow();

        assertThat(referencia).isEqualTo(new ClienteReferencia(7L, "Juan Pérez", true,
                ClienteReferencia.CondicionIva.MONOTRIBUTISTA, ClienteReferencia.TipoDocumento.CUIT, "20123456786",
                "Av. Luro 3200"));
        assertThat(referencia.identificado()).isTrue();
        org.mockito.Mockito.verify(repositorio, org.mockito.Mockito.never()).findScoped(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @EnumSource(CondicionIvaCliente.class)
    @DisplayName("cada condición de IVA del modelo llega al puerto con su mismo nombre, por buscar y por tomarParaVenta")
    void cadaCondicionTieneSuIgual(CondicionIvaCliente condicion) {
        Cliente c = Cliente.de(1L, "Cliente", null, null, condicion, null, null, null, null);
        when(repositorio.findScoped(7L)).thenReturn(Optional.of(c));
        when(repositorio.bloquearCuentaScoped(7L)).thenReturn(Optional.of(new CuentaFalsa("Cliente", BigDecimal.ZERO, null,
                true, condicion.name(), null, null, null)));

        assertThat(puerto.buscar(7L).orElseThrow().condicionIva().name()).isEqualTo(condicion.name());
        assertThat(puerto.tomarParaVenta(7L).orElseThrow().condicionIva().name()).isEqualTo(condicion.name());
    }

    @ParameterizedTest
    @EnumSource(TipoDocumento.class)
    @DisplayName("cada tipo de documento del modelo llega al puerto con su mismo nombre, por buscar y por tomarParaVenta")
    void cadaTipoDeDocumentoTieneSuIgual(TipoDocumento tipo) {
        String numero = tipo == TipoDocumento.DNI ? "12345678" : "20123456786";
        Cliente c = Cliente.de(1L, "Cliente", tipo, numero, CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
        when(repositorio.findScoped(7L)).thenReturn(Optional.of(c));
        when(repositorio.bloquearCuentaScoped(7L)).thenReturn(Optional.of(new CuentaFalsa("Cliente", BigDecimal.ZERO, null,
                true, "CONSUMIDOR_FINAL", tipo.name(), numero, null)));

        ClienteReferencia buscado = puerto.buscar(7L).orElseThrow();
        ClienteReferencia tomado = puerto.tomarParaVenta(7L).orElseThrow();

        assertThat(buscado.tipoDocumento().name()).isEqualTo(tipo.name());
        assertThat(buscado.numeroDocumento()).isEqualTo(numero);
        assertThat(tomado.tipoDocumento().name()).isEqualTo(tipo.name());
        assertThat(tomado.numeroDocumento()).isEqualTo(numero);
    }

    @Test
    @DisplayName("tomarParaVenta de un cliente desactivado lo marca inactivo (el estado es el del lock, no una foto vieja)")
    void tomarParaVentaInactivo() {
        when(repositorio.bloquearCuentaScoped(8L)).thenReturn(Optional.of(
                new CuentaFalsa("Viejo", new BigDecimal("10.00"), new BigDecimal("500.00"), false)));

        assertThat(puerto.tomarParaVenta(8L)).contains(consumidorFinal(8L, "Viejo", false));
    }

    @Test
    @DisplayName("tomarParaVenta de uno que no existe o es de otra ferretería no vuelve")
    void tomarParaVentaNoExiste() {
        when(repositorio.bloquearCuentaScoped(9L)).thenReturn(Optional.empty());

        assertThat(puerto.tomarParaVenta(9L)).isEmpty();
    }
}
