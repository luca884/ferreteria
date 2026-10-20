package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CCT3B1-310 (CC-057, CC-060, CC-061, CC-086, CC-092): la fachada de anular, SIN transacción (molde de CobranzaService). Valida el
 * cuerpo antes de leer nada, resuelve recibo → cliente en una transacción propia sin locks, reintenta los deadlocks (3 intentos)
 * y traduce el choque del UNIQUE de la anulación a 409 con una transacción NUEVA. Colaborador simulado.
 */
@ExtendWith(MockitoExtension.class)
class AnulacionServiceTest {

    static final Long RECIBO = 501L;
    static final Long CLIENTE = 5L;
    static final Long USUARIO = 9L;
    static final String MOTIVO = "Cobro mal cargado";

    @Mock
    RegistroAnulacion registro;

    AnulacionService service;

    CobroRegistrado anulado = new CobroRegistrado(null, List.of(), "Marta", false, "Ana");

    @BeforeEach
    void preparar() {
        service = new AnulacionService(registro);
    }

    private void clienteResuelto() {
        when(registro.clienteDelRecibo(RECIBO)).thenReturn(CLIENTE);
    }

    @Test
    @DisplayName("la fachada no tiene transacción: cada intento abre la suya en RegistroAnulacion")
    void sinTransaccion() throws NoSuchMethodException {
        assertThat(AnulacionService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AnulacionService.class.getMethod("anular", Long.class, String.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("CC-091 (W5): dentro de una transacción ajena NO se anula: la pre-resolución fijaría la foto antes del lock del cliente; se rechaza antes de tocar nada")
    void rechazaCorrerDentroDeUnaTransaccion() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no puede llamarse dentro de una transaccion");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(registro);
    }

    @Test
    @DisplayName("camino feliz: motivo recortado, recibo → cliente en tx aparte y UN intento de registrar con el cliente resuelto")
    void caminoFeliz() {
        clienteResuelto();
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO)).thenReturn(anulado);

        assertThat(service.anular(RECIBO, "  " + MOTIVO + "  ", USUARIO)).isSameAs(anulado);

        InOrder orden = inOrder(registro);
        orden.verify(registro).clienteDelRecibo(RECIBO);
        orden.verify(registro).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
        orden.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("el cuerpo gana al 404: motivo inválido y recibo inexistente dan 400 sin consultar el recibo")
    void elCuerpoGanaAl404() {
        assertThatThrownBy(() -> service.anular(RECIBO, "abc", USUARIO)).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsKey("motivo"));
        assertThatThrownBy(() -> service.anular(RECIBO, null, USUARIO)).isInstanceOf(DatosInvalidosException.class);

        verifyNoInteractions(registro);
    }

    @Test
    @DisplayName("recibo inexistente o ajeno: 404 antes de registrar")
    void reciboInexistente() {
        when(registro.clienteDelRecibo(RECIBO)).thenThrow(new ResourceNotFoundException("Recibo no encontrado"));

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Recibo no encontrado");
        verify(registro, never()).registrar(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("un deadlock se reintenta y la anulación sale en el segundo intento; el cliente se resuelve UNA vez")
    void reintentaUnDeadlock() {
        clienteResuelto();
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(anulado);

        assertThat(service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(anulado);
        verify(registro, times(2)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
        verify(registro, times(1)).clienteDelRecibo(RECIBO);
    }

    @Test
    @DisplayName("tres intentos con deadlock o timeout y se avisa que el sistema está ocupado (503), con el último error como causa; sin guardar y sin cuarto intento")
    void tresIntentosYSistemaOcupado() {
        clienteResuelto();
        DeadlockLoserDataAccessException ultimo = new DeadlockLoserDataAccessException("deadlock", null);
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(ultimo);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isInstanceOf(SistemaOcupadoException.class)
                .hasCause(ultimo).hasMessage("El sistema está ocupado, reintentá.");
        verify(registro, times(3)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
        assertThat(AnulacionService.MAX_INTENTOS).isEqualTo(3);
    }

    @Test
    @DisplayName("otros errores (de programación, de negocio) y los rechazos 409/422 no se reintentan")
    void otrasNoSeReintentan() {
        clienteResuelto();
        IllegalStateException roto = new IllegalStateException("roto");
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO)).thenThrow(roto);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(roto);
        verify(registro, times(1)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);

        CobranzaRechazadaException rechazo = CobranzaRechazadaException.reciboYaAnulado("Ana", LocalDateTime.of(2026, 10, 19, 17, 45));
        doThrow(rechazo).when(registro).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(rechazo);
        verify(registro, times(2)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
    }

    @Test
    @DisplayName("CC-061: el choque del UNIQUE de la anulación (1062) se traduce a 409 RECIBO_YA_ANULADO con quién y cuándo, en tx nueva: nunca 500")
    void duplicadoEs409() {
        clienteResuelto();
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO))
                .thenThrow(new DataIntegrityViolationException("uk_mcc_recibo_tipo", new SQLException("dup", "23000", 1062)));
        CobranzaRechazadaException ya = CobranzaRechazadaException.reciboYaAnulado("Ana", LocalDateTime.of(2026, 10, 19, 17, 45));
        doThrow(ya).when(registro).rechazoSiAnulado(RECIBO);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(ya);
        assertThat(ya.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
        verify(registro, times(1)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
    }

    @Test
    @DisplayName("un duplicado sin recibo anulado detrás relanza la excepción original (no se disfraza)")
    void duplicadoSinAnuladoSeRelanza() {
        clienteResuelto();
        DataIntegrityViolationException original = new DataIntegrityViolationException("uk", new SQLException("dup", "23000", 1062));
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO)).thenThrow(original);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(original);
        verify(registro).rechazoSiAnulado(RECIBO);
    }

    @Test
    @DisplayName("otro código de integridad (valor fuera de rango, 1264) se relanza sin buscar un recibo anulado")
    void otroCodigoDeIntegridadSeRelanza() {
        clienteResuelto();
        DataIntegrityViolationException fuera = new DataIntegrityViolationException("rango", new SQLException("rango", "22003", 1264));
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO)).thenThrow(fuera);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(fuera);
        verify(registro, never()).rechazoSiAnulado(anyLong());
    }

    @Test
    @DisplayName("sin código de MySQL (violación genérica) también se busca el recibo anulado, y si no está se relanza la original")
    void integridadSinCodigo() {
        clienteResuelto();
        DataIntegrityViolationException generica = new DataIntegrityViolationException("uk");
        when(registro.registrar(RECIBO, CLIENTE, MOTIVO, USUARIO)).thenThrow(generica);

        assertThatThrownBy(() -> service.anular(RECIBO, MOTIVO, USUARIO)).isSameAs(generica);
        verify(registro).rechazoSiAnulado(RECIBO);
        verify(registro, times(1)).registrar(RECIBO, CLIENTE, MOTIVO, USUARIO);
    }
}
