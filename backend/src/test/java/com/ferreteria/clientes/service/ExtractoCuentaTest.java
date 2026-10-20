package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.LineaDeExtracto;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.TotalesDelPeriodo;
import com.ferreteria.clientes.service.ExtractoCuenta.Extracto;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCT3B1-502 (CC-073, CC-075, CC-077, CC-079, CC-099/D12): el servicio del extracto sin Spring. Valida los parámetros ANTES de
 * consultar (400 con clave {@code desde}), acota {@code page}/{@code size}, traduce el filtro a los centinelas de la consulta
 * (D11) y arma el encabezado de la misma foto, sin lock ni escritura.
 */
class ExtractoCuentaTest {

    private static final LocalDateTime DESDE_ABIERTO = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime HASTA_ABIERTO = LocalDateTime.of(9999, 12, 31, 0, 0);
    private static final EnumSet<TipoMovimientoCuenta> TODOS = EnumSet.allOf(TipoMovimientoCuenta.class);

    private ClienteRepository clientes;
    private MovimientoCuentaClienteRepository libro;
    private Cliente cliente;
    private ExtractoCuenta extracto;

    @BeforeEach
    void armar() {
        clientes = mock(ClienteRepository.class);
        libro = mock(MovimientoCuentaClienteRepository.class);
        cliente = mock(Cliente.class);
        when(cliente.getRazonSocial()).thenReturn("Ferretería Don Pepe");
        when(cliente.getSaldo()).thenReturn(new BigDecimal("1200.00"));
        when(cliente.isActivo()).thenReturn(true);
        when(clientes.findScoped(7L)).thenReturn(Optional.of(cliente));
        when(libro.contarLineasScoped(any(), any(), any(), any())).thenReturn(0L);
        when(libro.lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        doReturn(totales("0", "0")).when(libro).totalesDelPeriodoScoped(any(), any(), any(), any());
        when(libro.saldoAntesDeScoped(any(), any())).thenReturn(BigDecimal.ZERO);
        when(libro.saldoHastaScoped(any(), any())).thenReturn(BigDecimal.ZERO);
        extracto = new ExtractoCuenta(clientes, libro);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

    private static FiltroExtracto filtro(LocalDate desde, LocalDate hasta, TipoMovimientoCuenta tipo, Integer page, Integer size) {
        return new FiltroExtracto(desde, hasta, tipo, page, size, false);
    }

    private static FiltroExtracto sinFiltros() {
        return filtro(null, null, null, null, null);
    }

    private static TotalesDelPeriodo totales(String debe, String haber) {
        TotalesDelPeriodo t = mock(TotalesDelPeriodo.class);
        when(t.getDebe()).thenReturn(new BigDecimal(debe));
        when(t.getHaber()).thenReturn(new BigDecimal(haber));
        return t;
    }

    private static LineaDeExtracto linea(long id, String tipo, String debe, String haber, String saldo) {
        LineaDeExtracto l = mock(LineaDeExtracto.class);
        when(l.getId()).thenReturn(id);
        when(l.getTipo()).thenReturn(tipo);
        when(l.getDebe()).thenReturn(new BigDecimal(debe));
        when(l.getHaber()).thenReturn(new BigDecimal(haber));
        when(l.getSaldo()).thenReturn(new BigDecimal(saldo));
        when(l.getReferencia()).thenReturn("Ref " + id);
        when(l.getFecha()).thenReturn(LocalDateTime.of(2026, 10, 1, 9, 0).plusMinutes(id));
        return l;
    }

    private static List<LineaDeExtracto> lineas(int cuantas) {
        List<LineaDeExtracto> lineas = new ArrayList<>();
        for (int i = cuantas; i >= 1; i--) {
            lineas.add(linea(i, "VENTA", "1.00", "0", String.valueOf(i)));
        }
        return lineas;
    }

    @SuppressWarnings("unchecked")
    private Collection<TipoMovimientoCuenta> tiposPedidos() {
        ArgumentCaptor<Collection<TipoMovimientoCuenta>> tipos = ArgumentCaptor.forClass(Collection.class);
        verify(libro).lineasDelExtractoScoped(eq(7L), any(), any(), tipos.capture(), anyInt(), anyInt());
        return tipos.getValue();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Validación y 404
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-073: desde > hasta → 400 con la clave «desde» y sin consultar ni el cliente ni el libro")
    void desdeMayorQueHasta() {
        assertThatThrownBy(() -> extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), null, null, null)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                    assertThat(e.errors()).containsOnlyKeys("desde");
                    assertThat(e.errors().get("desde")).isEqualTo("La fecha desde no puede ser posterior a hasta.");
                });

        verifyNoInteractions(clientes, libro);
    }

    @Test
    @DisplayName("CC-073: desde == hasta es válido (un solo día)")
    void mismoDiaEsValido() {
        LocalDate dia = LocalDate.of(2026, 10, 10);

        extracto.extracto(7L, filtro(dia, dia, null, null, null));

        verify(libro).contarLineasScoped(7L, dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), TODOS);
    }

    @Test
    @DisplayName("CC-072/CC-080: cliente ajeno o inexistente → 404 (findScoped) sin tocar el libro")
    void clienteAjenoEs404() {
        when(clientes.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> extracto.extracto(99L, sinFiltros())).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Cliente no encontrado");

        verifyNoInteractions(libro);
    }

    @Test
    @DisplayName("S-5: un cliente DESACTIVADO se consulta igual")
    void desactivadoSeConsulta() {
        when(cliente.isActivo()).thenReturn(false);

        Extracto resultado = extracto.extracto(7L, sinFiltros());

        assertThat(resultado.clienteId()).isEqualTo(7L);
        assertThat(resultado.clienteNombre()).isEqualTo("Ferretería Don Pepe");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // page y size
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-073 / S-3: size por defecto 20; size=500 → 100; size ≤ 0 → 1; page < 0 → 0; page y size nulos → 0 y 20")
    void sizeYPageAcotados() {
        assertThat(extracto.extracto(7L, sinFiltros()).size()).isEqualTo(20);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, 500)).size()).isEqualTo(100);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, 100)).size()).isEqualTo(100);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, 101)).size()).isEqualTo(100);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, 0)).size()).isEqualTo(1);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, -5)).size()).isEqualTo(1);
        assertThat(extracto.extracto(7L, filtro(null, null, null, 0, 37)).size()).isEqualTo(37);
        assertThat(extracto.extracto(7L, filtro(null, null, null, -3, 10)).page()).isZero();
        assertThat(extracto.extracto(7L, sinFiltros()).page()).isZero();
    }

    @Test
    @DisplayName("la página 2 de tamaño 10 pide límite 10 y offset 20; con 45 líneas son 5 páginas, con 0 líneas 0 páginas")
    void limiteOffsetYPaginas() {
        when(libro.contarLineasScoped(any(), any(), any(), any())).thenReturn(45L);

        Extracto resultado = extracto.extracto(7L, filtro(null, null, null, 2, 10));

        verify(libro).lineasDelExtractoScoped(7L, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 10, 20);
        assertThat(resultado.totalLineas()).isEqualTo(45);
        assertThat(resultado.totalPaginas()).isEqualTo(5);
        when(libro.contarLineasScoped(any(), any(), any(), any())).thenReturn(0L);
        Extracto vacio = extracto.extracto(7L, sinFiltros());
        assertThat(vacio.totalPaginas()).isZero();
        assertThat(vacio.lineas()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Centinelas y rango de fechas
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D11: sin desde, hasta ni tipo se bindean los centinelas 1000-01-01, 9999-12-31 y todos los tipos")
    void centinelas() {
        extracto.extracto(7L, sinFiltros());

        verify(libro).lineasDelExtractoScoped(7L, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 20, 0);
        verify(libro).contarLineasScoped(7L, DESDE_ABIERTO, HASTA_ABIERTO, TODOS);
        verify(libro).totalesDelPeriodoScoped(7L, DESDE_ABIERTO, HASTA_ABIERTO, TODOS);
    }

    @Test
    @DisplayName("CC-075: desde inclusive = desde 00:00; hasta inclusive = < hasta + 1 día 00:00 (sin 23:59:59.999999); tipo deja solo ese tipo")
    void rangoInclusivo() {
        extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), TipoMovimientoCuenta.COBRO, 0, 20));

        verify(libro).lineasDelExtractoScoped(7L, LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 11, 0, 0),
                EnumSet.of(TipoMovimientoCuenta.COBRO), 20, 0);
        assertThat(tiposPedidos()).containsExactly(TipoMovimientoCuenta.COBRO);
    }

    @Test
    @DisplayName("CC-073: con un solo extremo el otro queda abierto (centinela)")
    void unSoloExtremo() {
        extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 1), null, null, null, null));
        verify(libro).contarLineasScoped(7L, LocalDateTime.of(2026, 10, 1, 0, 0), HASTA_ABIERTO, TODOS);

        extracto.extracto(7L, filtro(null, LocalDate.of(2026, 10, 10), null, null, null));
        verify(libro).contarLineasScoped(7L, DESDE_ABIERTO, LocalDateTime.of(2026, 10, 11, 0, 0), TODOS);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Encabezado
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-077: saldoInicial = 0 sin desde (sin consultarlo); con desde, el del libro anterior a desde 00:00")
    void saldoInicial() {
        assertThat(extracto.extracto(7L, sinFiltros()).saldoInicial()).isEqualByComparingTo("0");
        verify(libro, never()).saldoAntesDeScoped(any(), any());

        when(libro.saldoAntesDeScoped(7L, LocalDateTime.of(2026, 10, 5, 0, 0))).thenReturn(new BigDecimal("300.00"));
        assertThat(extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 5), null, null, null, null)).saldoInicial())
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("CC-077: saldoFinal = el del libro completo hasta hasta inclusive (SIN filtro de tipo); sin hasta = el saldo actual del cliente")
    void saldoFinal() {
        when(libro.saldoHastaScoped(7L, LocalDateTime.of(2026, 10, 11, 0, 0))).thenReturn(new BigDecimal("800.00"));

        Extracto conHasta = extracto.extracto(7L, filtro(null, LocalDate.of(2026, 10, 10), TipoMovimientoCuenta.COBRO, null, null));
        Extracto sinHasta = extracto.extracto(7L, sinFiltros());

        assertThat(conHasta.saldoFinal()).isEqualByComparingTo("800.00");
        assertThat(sinHasta.saldoFinal()).isEqualByComparingTo("1200.00");
        verify(libro, org.mockito.Mockito.times(1)).saldoHastaScoped(any(), any());
    }

    @Test
    @DisplayName("CC-077: saldoActual = cliente.saldo; totalDebe y totalHaber salen de las líneas que pasan el filtro (consulta propia) y saldoInicial + debe − haber = saldoFinal sin filtro de tipo")
    void totalesYSaldoActual() {
        doReturn(totales("900.00", "0.00")).when(libro).totalesDelPeriodoScoped(7L, LocalDateTime.of(2026, 10, 5, 0, 0), HASTA_ABIERTO, TODOS);
        when(libro.saldoAntesDeScoped(7L, LocalDateTime.of(2026, 10, 5, 0, 0))).thenReturn(new BigDecimal("300.00"));

        Extracto resultado = extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 5), null, null, null, null));

        assertThat(resultado.saldoActual()).isEqualByComparingTo("1200.00");
        assertThat(resultado.totalDebe()).isEqualByComparingTo("900.00");
        assertThat(resultado.totalHaber()).isEqualByComparingTo("0.00");
        assertThat(resultado.saldoInicial().add(resultado.totalDebe()).subtract(resultado.totalHaber()))
                .isEqualByComparingTo(resultado.saldoFinal());
    }

    @Test
    @DisplayName("el filtro y el período se devuelven tal como se pidieron (desde, hasta y tipo; nulos si no hubo)")
    void devuelveElFiltro() {
        Extracto con = extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), TipoMovimientoCuenta.VENTA, 0, 20));
        Extracto sin = extracto.extracto(7L, sinFiltros());

        assertThat(con.desde()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(con.hasta()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(con.tipo()).isEqualTo(TipoMovimientoCuenta.VENTA);
        assertThat(sin.desde()).isNull();
        assertThat(sin.hasta()).isNull();
        assertThat(sin.tipo()).isNull();
        assertThat(sin.truncado()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Líneas
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-078: cada línea convierte el tipo a enum y los 0/1 (Boolean, Number o null) a booleano, y copia el resto tal cual")
    void conversionDeLineas() {
        LineaDeExtracto cobro = linea(2, "COBRO", "0.00", "400.00", "600.00");
        when(cobro.getAnulado()).thenReturn(1L); // una expresión: Number
        when(cobro.getExcedioLimite()).thenReturn(false);
        when(cobro.getReciboId()).thenReturn(14L);
        when(cobro.getUsuario()).thenReturn("Ana");
        LineaDeExtracto venta = linea(1, "VENTA", "1000.00", "0.00", "1000.00");
        when(venta.getAnulado()).thenReturn(0);
        when(venta.getExcedioLimite()).thenReturn(true); // TINYINT(1): Boolean
        when(venta.getAutorizadoPor()).thenReturn("Dueño");
        when(venta.getComprobanteId()).thenReturn(33L);
        LineaDeExtracto anulacion = linea(3, "ANULACION", "400.00", "0.00", "1000.00");
        when(anulacion.getMotivo()).thenReturn("Cobro mal cargado");
        doReturn(List.of(anulacion, cobro, venta)).when(libro).lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt());

        List<ExtractoCuenta.Linea> lineas = extracto.extracto(7L, sinFiltros()).lineas();

        assertThat(lineas).extracting(ExtractoCuenta.Linea::tipo)
                .containsExactly(TipoMovimientoCuenta.ANULACION, TipoMovimientoCuenta.COBRO, TipoMovimientoCuenta.VENTA);
        assertThat(lineas).extracting(ExtractoCuenta.Linea::anulado).containsExactly(false, true, false);
        assertThat(lineas).extracting(ExtractoCuenta.Linea::excedioLimite).containsExactly(false, false, true);
        assertThat(lineas.get(0).motivo()).isEqualTo("Cobro mal cargado");
        assertThat(lineas.get(1).reciboId()).isEqualTo(14L);
        assertThat(lineas.get(1).usuario()).isEqualTo("Ana");
        assertThat(lineas.get(2).autorizadoPor()).isEqualTo("Dueño");
        assertThat(lineas.get(2).comprobanteId()).isEqualTo(33L);
        assertThat(lineas.get(1).saldo()).isEqualByComparingTo("600.00");
        assertThat(lineas.get(1).haber()).isEqualByComparingTo("400.00");
        assertThat(lineas.get(0).id()).isEqualTo(3L);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Impresión (D12)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D12: impresión = página 0 con límite 2001 (ignora page y size); con 2001 líneas se recortan a 2000 y truncado = true")
    void impresionTruncada() {
        doReturn(lineas(2001)).when(libro).lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt());
        when(libro.contarLineasScoped(any(), any(), any(), any())).thenReturn(2500L);

        Extracto resultado = extracto.extracto(7L, new FiltroExtracto(null, null, null, 3, 5, true));

        verify(libro).lineasDelExtractoScoped(7L, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 2001, 0);
        assertThat(resultado.truncado()).isTrue();
        assertThat(resultado.lineas()).hasSize(2000);
        assertThat(resultado.lineas().get(0).id()).as("se conservan las 2000 más nuevas").isEqualTo(2001L);
        assertThat(resultado.lineas().get(1999).id()).isEqualTo(2L);
        assertThat(resultado.page()).isZero();
        assertThat(resultado.size()).isEqualTo(2000);
        assertThat(resultado.totalLineas()).isEqualTo(2500);
    }

    @Test
    @DisplayName("D12: con exactamente 2000 líneas, o menos, no hay truncado")
    void impresionSinTruncado() {
        doReturn(lineas(2000)).when(libro).lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt());
        assertThat(extracto.extracto(7L, new FiltroExtracto(null, null, null, null, null, true)).truncado()).isFalse();
        assertThat(extracto.extracto(7L, new FiltroExtracto(null, null, null, null, null, true)).lineas()).hasSize(2000);

        doReturn(lineas(5)).when(libro).lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt());
        Extracto poco = extracto.extracto(7L, new FiltroExtracto(null, null, null, null, null, true));
        assertThat(poco.truncado()).isFalse();
        assertThat(poco.lineas()).hasSize(5);
    }

    @Test
    @DisplayName("la consulta de pantalla nunca marca truncado, aunque traiga 100 líneas")
    void pantallaNoTrunca() {
        doReturn(lineas(100)).when(libro).lineasDelExtractoScoped(any(), any(), any(), any(), anyInt(), anyInt());

        Extracto resultado = extracto.extracto(7L, filtro(null, null, null, 0, 100));

        assertThat(resultado.truncado()).isFalse();
        assertThat(resultado.lineas()).hasSize(100);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Solo lectura, sin locks
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-079: solo lee — del cliente solo findScoped (nada de bloquear*/save*) y del libro solo las cinco consultas del extracto")
    void soloLectura() {
        extracto.extracto(7L, filtro(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), null, 0, 20));

        verify(clientes).findScoped(7L);
        verifyNoMoreInteractions(clientes);
        verify(libro).lineasDelExtractoScoped(eq(7L), any(), any(), any(), anyInt(), anyInt());
        verify(libro).contarLineasScoped(eq(7L), any(), any(), any());
        verify(libro).saldoAntesDeScoped(eq(7L), any());
        verify(libro).totalesDelPeriodoScoped(eq(7L), any(), any(), any());
        verify(libro).saldoHastaScoped(eq(7L), any());
        verifyNoMoreInteractions(libro);
    }

    @Test
    @DisplayName("CC-079: el servicio es una transacción de SOLO LECTURA (una foto)")
    void esTransaccionDeSoloLectura() throws NoSuchMethodException {
        Transactional enMetodo = ExtractoCuenta.class.getMethod("extracto", Long.class, FiltroExtracto.class)
                .getAnnotation(Transactional.class);
        Transactional enClase = ExtractoCuenta.class.getAnnotation(Transactional.class);
        Transactional efectiva = enMetodo != null ? enMetodo : enClase;

        assertThat(efectiva).as("@Transactional en extracto o en la clase").isNotNull();
        assertThat(efectiva.readOnly()).isTrue();
    }
}
