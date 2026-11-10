package com.ferreteria.clientes.service;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.LineaDeExtracto;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.TotalesDelPeriodo;
import com.ferreteria.common.RangoDeFechas;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El extracto de la cuenta corriente de un cliente (CC-072..CC-080): líneas con el saldo REAL después de cada una (la ventana
 * corre sobre todo el libro y los filtros van afuera, ver {@code lineasDelExtracto}), encabezado y paginación. Es de SOLO
 * LECTURA: una transacción {@code readOnly} = una foto consistente de la base, sin ningún lock (no toma el turno de ninguna
 * cobranza, venta, anulación o desactivación; CC-079), y no escribe nada. Separado de {@link CuentaCorrienteService} para no
 * inflarlo y porque ese es el único que escribe el libro, las imputaciones y el saldo (lo vigila ModulosArchTest).
 *
 * <p>Orden de las lecturas: el cliente primero ({@code findScoped}, 404 si es ajeno o inexistente; uno DESACTIVADO se consulta,
 * S-5): ahí queda fijada la foto. Después el conteo, las líneas y el encabezado. {@code saldoActual} es el saldo del cliente en
 * esa misma foto; sin {@code hasta} el saldo final es ese mismo (Σ del libro = saldo, I1).
 *
 * <p>Las fechas del libro son la hora local de Buenos Aires: {@code desde} es inclusivo desde las 00:00 y {@code hasta} inclusivo
 * hasta el final de su día, que se expresa como «menor que hasta + 1 día 00:00» (así entran los 23:59:59.999999). Sin filtro se
 * bindean centinelas (D11): nunca un {@code null} en la consulta nativa.
 */
@Service
@Transactional(readOnly = true)
public class ExtractoCuenta {

    private static final LocalDateTime DESDE_ABIERTO = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime HASTA_ABIERTO = LocalDateTime.of(9999, 12, 31, 0, 0);
    private static final LocalDate FECHA_MINIMA = LocalDate.of(1000, 1, 1);
    private static final LocalDate FECHA_MAXIMA = LocalDate.of(9999, 12, 30);
    private static final String NO_ENCONTRADO = "Cliente no encontrado";

    private final ClienteRepository clientes;
    private final MovimientoCuentaClienteRepository libro;

    public ExtractoCuenta(ClienteRepository clientes, MovimientoCuentaClienteRepository libro) {
        this.clientes = clientes;
        this.libro = libro;
    }

    /** Una línea del extracto: el movimiento y el saldo del cliente DESPUÉS de él. */
    public record Linea(Long id, LocalDateTime fecha, TipoMovimientoCuenta tipo, String referencia, BigDecimal debe,
            BigDecimal haber, BigDecimal saldo, String usuario, Long reciboId, Long comprobanteId, String motivo,
            boolean excedioLimite, String autorizadoPor, boolean anulado) {}

    /**
     * El extracto: encabezado (saldos y totales), la página de líneas (más nueva primero) y su paginación. {@code truncado} solo
     * es verdadero en una impresión que superó el tope. El documento del cliente va para el impreso (CC-099); un cliente sin
     * documento lo trae nulo.
     */
    public record Extracto(Long clienteId, String clienteNombre, TipoDocumento tipoDocumento, String numeroDocumento,
            BigDecimal saldoActual, LocalDate desde, LocalDate hasta,
            TipoMovimientoCuenta tipo, BigDecimal saldoInicial, BigDecimal totalDebe, BigDecimal totalHaber,
            BigDecimal saldoFinal, List<Linea> lineas, int page, int size, long totalLineas, int totalPaginas,
            boolean truncado) {}

    public Extracto extracto(Long clienteId, FiltroExtracto filtro) {
        if (filtro.desde() != null && filtro.hasta() != null && filtro.desde().isAfter(filtro.hasta())) {
            // El texto único de los rangos (bugs-relevamiento-celular, S4); la clave sigue siendo «desde».
            throw new DatosInvalidosException(Map.of("desde", RangoDeFechas.HASTA_ANTERIOR_A_DESDE));
        }
        // Las fechas se acotan al rango que la base y el cálculo de «hasta + 1 día» soportan (nunca un 500, CC-073)
        if (fueraDeRango(filtro.desde())) {
            throw new DatosInvalidosException(Map.of("desde", "La fecha desde tiene que estar entre el 01/01/1000 y el 30/12/9999."));
        }
        if (fueraDeRango(filtro.hasta())) {
            throw new DatosInvalidosException(Map.of("hasta", "La fecha hasta tiene que estar entre el 01/01/1000 y el 30/12/9999."));
        }
        Cliente cliente = clientes.findScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));

        LocalDateTime desde = filtro.desde() == null ? DESDE_ABIERTO : filtro.desde().atStartOfDay();
        LocalDateTime hastaExclusivo = filtro.hasta() == null ? HASTA_ABIERTO : filtro.hasta().plusDays(1).atStartOfDay();
        EnumSet<TipoMovimientoCuenta> tipos = filtro.tipo() == null ? EnumSet.allOf(TipoMovimientoCuenta.class)
                : EnumSet.of(filtro.tipo());

        int page;
        int size;
        int limite;
        int offset;
        if (filtro.impresion()) {
            // una sola consulta = una sola foto: página 0 y una línea de más para saber si se pasó del tope
            page = 0;
            size = FiltroExtracto.TOPE_IMPRESION;
            limite = FiltroExtracto.TOPE_IMPRESION + 1;
            offset = 0;
        } else {
            page = Math.max(filtro.page() == null ? 0 : filtro.page(), 0);
            size = Math.min(Math.max(filtro.size() == null ? FiltroExtracto.TAMANO_POR_DEFECTO : filtro.size(), 1),
                    FiltroExtracto.TAMANO_MAXIMO);
            limite = size;
            long desplazamiento = (long) page * size;
            if (desplazamiento > Integer.MAX_VALUE) {
                throw new DatosInvalidosException(Map.of("page", "La página pedida está fuera de rango."));
            }
            offset = (int) desplazamiento;
        }

        long totalLineas = libro.contarLineasScoped(clienteId, desde, hastaExclusivo, tipos);
        List<LineaDeExtracto> filas = libro.lineasDelExtractoScoped(clienteId, desde, hastaExclusivo, tipos, limite, offset);
        boolean truncado = filtro.impresion() && filas.size() > FiltroExtracto.TOPE_IMPRESION;
        List<LineaDeExtracto> aMostrar = truncado ? filas.subList(0, FiltroExtracto.TOPE_IMPRESION) : filas;
        List<Linea> lineas = new ArrayList<>(aMostrar.size());
        for (LineaDeExtracto fila : aMostrar) {
            lineas.add(linea(fila));
        }

        BigDecimal saldoInicial = filtro.desde() == null ? BigDecimal.ZERO : libro.saldoAntesDeScoped(clienteId, desde);
        TotalesDelPeriodo totales = libro.totalesDelPeriodoScoped(clienteId, desde, hastaExclusivo, tipos);
        // el saldo final es el del libro COMPLETO hasta hasta, sin filtro de tipo; sin hasta, el saldo actual (I1: Σ del libro)
        BigDecimal saldoFinal = filtro.hasta() == null ? cliente.getSaldo() : libro.saldoHastaScoped(clienteId, hastaExclusivo);
        int totalPaginas = (int) ((totalLineas + size - 1) / size);

        return new Extracto(clienteId, cliente.getRazonSocial(), cliente.getTipoDocumento(), cliente.getNumeroDocumento(),
                cliente.getSaldo(), filtro.desde(), filtro.hasta(),
                filtro.tipo(), saldoInicial, totales.getDebe(), totales.getHaber(), saldoFinal, lineas, page, size,
                totalLineas, totalPaginas, truncado);
    }

    /** Fuera de [1000-01-01, 9999-12-30]: {@code hasta + 1 día} del 9999-12-31 ya es el año 10000, que la base no guarda. */
    private static boolean fueraDeRango(LocalDate fecha) {
        return fecha != null && (fecha.isBefore(FECHA_MINIMA) || fecha.isAfter(FECHA_MAXIMA));
    }

    private static Linea linea(LineaDeExtracto fila) {
        return new Linea(fila.getId(), fila.getFecha(), TipoMovimientoCuenta.valueOf(fila.getTipo()), fila.getReferencia(),
                fila.getDebe(), fila.getHaber(), fila.getSaldo(), fila.getUsuario(), fila.getReciboId(),
                fila.getComprobanteId(), fila.getMotivo(), verdadero(fila.getExcedioLimite()), fila.getAutorizadoPor(),
                verdadero(fila.getAnulado()));
    }

    /** El driver entrega el booleano de MySQL como {@code Boolean} (TINYINT(1)) o como número 0/1 (una expresión). */
    private static boolean verdadero(Object valor) {
        if (valor instanceof Boolean booleano) {
            return booleano;
        }
        return valor instanceof Number numero && numero.intValue() != 0;
    }
}
