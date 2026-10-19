package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.Cupo;
import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.PartidasAbiertas;
import com.ferreteria.clientes.model.Referencias;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputada;
import com.ferreteria.clientes.repository.ImputacionRepository.ImputadoDeComprobante;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.DebitoDeComprobante;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La cuenta corriente de los clientes (CC-001 a CC-010, CC-026 a CC-051). Es la ÚNICA clase que escribe el saldo del cliente,
 * el libro y las imputaciones (lo vigila ModulosArchTest): el saldo es una caché que siempre vale Σ(debe − haber) del libro, y
 * los tres se escriben juntos en la transacción de la venta o de la cobranza, bajo el lock de la fila del cliente (que es el
 * mutex de TODO el estado de cuenta: libro, imputaciones y saldo).
 *
 * <p>Lee el estado SIEMPRE con {@code bloquearCuentaScoped} (lectura actual de escalares): ni {@code findScoped}, que
 * devolvería la instancia ya gestionada con el saldo de antes, ni un {@code SUM} del libro, que en REPEATABLE READ vería la
 * foto del inicio de la transacción. El saldo se escribe relativo ({@code saldo = saldo + delta}), nunca con un valor leído.
 */
@Service
public class CuentaCorrienteService implements CuentaCorrienteParaVenta, CuentaCorrienteParaCobranza {

    private static final String NO_ENCONTRADO = "Cliente no encontrado";
    private static final String COMPROBANTE_NO_ENCONTRADO = "Comprobante no encontrado en la cuenta del cliente";

    private final ClienteRepository clientes;
    private final MovimientoCuentaClienteRepository libro;
    private final ImputacionRepository imputaciones;
    private final Clock reloj;

    public CuentaCorrienteService(ClienteRepository clientes, MovimientoCuentaClienteRepository libro,
            ImputacionRepository imputaciones, Clock reloj) {
        this.clientes = clientes;
        this.libro = libro;
        this.imputaciones = imputaciones;
        this.reloj = reloj;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CupoCuenta verificarCupo(Long clienteId, BigDecimal importe) {
        CuentaBloqueada cuenta = cuentaBloqueada(clienteId);
        return cupo(cuenta.getLimiteCuentaCorriente(), cuenta.getSaldo(), importe);
    }

    private static CupoCuenta cupo(BigDecimal limite, BigDecimal saldo, BigDecimal importe) {
        Cupo cupo = Cupo.de(limite, saldo, importe);
        return new CupoCuenta(cupo.habilitada(), limite, saldo, cupo.disponible(), cupo.exceso());
    }

    /**
     * La cuenta del cliente, con «alcanza» si viene el importe (CC-007). Es un consejo para el cobro: lectura plana, sin lock;
     * la decisión real la toma la venta bajo lock. {@code conCifras} es el rol: el Dueño ve límite, saldo y disponible; el
     * Vendedor solo si está habilitada y si alcanza, y las cifras ni salen de este método.
     */
    @Transactional(readOnly = true)
    public CuentaDelCliente consultar(Long clienteId, BigDecimal importe, boolean conCifras) {
        if (importe != null && importe.signum() < 0) {
            throw new DatosInvalidosException(Map.of("importe", "El importe no puede ser negativo."));
        }
        Cliente cliente = clientes.findScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
        return vista(cliente.getLimiteCuentaCorriente(), cliente.getSaldo(), importe, conCifras);
    }

    /**
     * Fija el límite (CC-018): null deshabilita la cuenta, 0 la habilita sin crédito. Bajarlo por debajo del saldo o
     * deshabilitar con saldo está permitido: solo frena las ventas nuevas. Toma antes la fila del cliente (la misma que toma
     * una venta), así que el saldo que devuelve es el último confirmado. Devuelve la vista del Dueño.
     */
    @Transactional
    public CuentaDelCliente cambiarLimite(Long clienteId, BigDecimal limite) {
        CuentaBloqueada cuenta = cuentaBloqueada(clienteId);
        Cliente cliente = clientes.findScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
        cliente.cambiarLimite(limite);
        return vista(limite, cuenta.getSaldo(), null, true);
    }

    private static CuentaDelCliente vista(BigDecimal limite, BigDecimal saldo, BigDecimal importe, boolean conCifras) {
        CupoCuenta cupo = cupo(limite, saldo, importe == null ? BigDecimal.ZERO : importe);
        Boolean alcanza = importe == null ? null : cupo.alcanza();
        return conCifras
                ? new CuentaDelCliente(cupo.habilitada(), alcanza, limite, saldo, cupo.disponible())
                : new CuentaDelCliente(cupo.habilitada(), alcanza, null, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void asentarVenta(AsientoVenta asiento) {
        List<CargoPago> pagos = asiento.pagos();
        if (pagos == null || pagos.isEmpty()) {
            throw new IllegalArgumentException("Una venta a cuenta corriente tiene al menos un pago.");
        }
        if (pagos.stream().anyMatch(p -> p.monto() == null || p.monto().signum() <= 0)) {
            throw new IllegalArgumentException("El monto de un pago a cuenta corriente tiene que ser mayor que cero.");
        }
        cuentaBloqueada(asiento.clienteId()); // ya la tiene tomada la venta: acá se re-confirma que existe y es de la ferretería
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        LocalDateTime fecha = LocalDateTime.now(reloj);
        String referencia = Referencias.venta(asiento.comprobanteNumero());
        libro.saveAll(pagos.stream()
                .map(p -> MovimientoCuentaCliente.venta(ferreteriaId, asiento.clienteId(), asiento.comprobanteId(),
                        p.pagoId(), referencia, p.monto(), fecha, asiento.usuarioId(), asiento.autorizadoPor()))
                .toList());
        BigDecimal total = pagos.stream().map(CargoPago::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        int filas = clientes.sumarSaldoScoped(asiento.clienteId(), total);
        if (filas != 1) {
            // El libro ya está escrito: sin la fila del saldo divergirían. Es un estado ilegal (500 y rollback), no un 4xx.
            throw new IllegalStateException("El saldo del cliente " + asiento.clienteId()
                    + " no se actualizó (filas afectadas: " + filas + "): el libro y el saldo divergirían.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cobranza (CC-026 a CC-033, CC-037, CC-048, CC-051)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Asienta el cobro de un recibo (design §4, pasos a–h). Una sola puerta de escritura, y se defiende sola: no confía en que
     * el llamador haya tomado al cliente ni en la foto de la transacción.
     * <ol>
     *   <li>re-toma la fila del cliente ({@code cuentaBloqueada}: no-op si ya la tiene) — NINGÚN SELECT plano antes;
     *   <li>débitos de los comprobantes imputados (lectura plana, monótona: una foto vieja solo SUBestima la deuda);
     *   <li>ids de sus partidas del libro, ordenados, y {@code FOR UPDATE} por PK exacta en orden ascendente (sin gap locks,
     *       sin deadlock entre cobranzas que imputan en distinto orden);
     *   <li>imputado de esos comprobantes con {@code FOR UPDATE} = lectura ACTUAL (un imputado viejo sobre-estimaría el
     *       pendiente y dejaría sobre-imputar: es la única lectura crítica);
     *   <li>exceso → {@link ImputacionInvalidaException} (rollback total); si no, guarda las imputaciones por comprobante
     *       ascendente, UN COBRO con haber = total y baja el saldo con un UPDATE relativo.
     * </ol>
     * Sin imputaciones (adelanto / todo a cuenta) solo toma al cliente y escribe el COBRO: no hay pendiente que validar y el
     * límite no aplica (CC-027).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ImputacionAsentada> asentarCobro(AsientoCobro asiento) {
        List<Imputar> pedidas = validar(asiento);
        String referencia = Referencias.recibo(asiento.reciboNumero());
        Long clienteId = asiento.clienteId();
        cuentaBloqueada(clienteId); // ya la tiene tomada quien llama: acá se re-toma y se confirma que existe y es de la ferretería
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        List<ImputacionAsentada> asentadas = pedidas.isEmpty() ? List.of() : imputar(asiento, pedidas, ferreteriaId);
        libro.save(MovimientoCuentaCliente.cobro(ferreteriaId, clienteId, asiento.reciboId(), referencia, asiento.total(),
                LocalDateTime.now(reloj), asiento.usuarioId()));
        int filas = clientes.sumarSaldoScoped(clienteId, asiento.total().negate());
        if (filas != 1) {
            // El libro ya está escrito: sin la fila del saldo divergirían. Es un estado ilegal (500 y rollback), no un 4xx.
            throw new IllegalStateException("El saldo del cliente " + clienteId
                    + " no se actualizó (filas afectadas: " + filas + "): el libro y el saldo divergirían.");
        }
        return asentadas;
    }

    /** Lo de programador (ventas ya validó con 400): total e importes positivos y de hasta 2 decimales, sin repetidos, Σ ≤ total. */
    private static List<Imputar> validar(AsientoCobro asiento) {
        if (asiento.clienteId() == null || asiento.reciboId() == null) {
            throw new IllegalArgumentException("El cobro necesita el cliente y el recibo.");
        }
        Referencias.recibo(asiento.reciboNumero()); // número no positivo → IllegalArgumentException
        BigDecimal total = asiento.total();
        if (total == null || total.signum() <= 0 || total.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El total del cobro tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        if (asiento.imputaciones() == null) {
            throw new IllegalArgumentException("Las imputaciones del cobro no pueden ser null (sin imputar es una lista vacía).");
        }
        Set<Long> vistos = new HashSet<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (Imputar imputar : asiento.imputaciones()) {
            if (imputar.comprobanteId() == null) {
                throw new IllegalArgumentException("Cada imputación necesita su comprobante.");
            }
            BigDecimal importe = imputar.importe();
            if (importe == null || importe.signum() <= 0 || importe.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("El importe a imputar al comprobante " + imputar.comprobanteId()
                        + " tiene que ser mayor que cero y de hasta 2 decimales.");
            }
            if (!vistos.add(imputar.comprobanteId())) {
                throw new IllegalArgumentException("El comprobante " + imputar.comprobanteId() + " viene dos veces.");
            }
            suma = suma.add(importe);
        }
        if (suma.compareTo(total) > 0) {
            throw new IllegalArgumentException("Lo imputado supera el total del cobro.");
        }
        return asiento.imputaciones().stream().sorted(Comparator.comparing(Imputar::comprobanteId)).toList();
    }

    private List<ImputacionAsentada> imputar(AsientoCobro asiento, List<Imputar> pedidas, Long ferreteriaId) {
        Long clienteId = asiento.clienteId();
        List<Long> comprobanteIds = pedidas.stream().map(Imputar::comprobanteId).toList(); // ya ascendentes
        List<DebitoDeComprobante> debitos = libro.debitosDeComprobantesScoped(clienteId, comprobanteIds);
        Set<Long> conDebito = debitos.stream().map(DebitoDeComprobante::getComprobanteId).collect(Collectors.toSet());
        if (!conDebito.containsAll(comprobanteIds)) {
            throw new ResourceNotFoundException(COMPROBANTE_NO_ENCONTRADO);
        }
        // Orden de locks: partidas por PK ascendente (defensa en profundidad; el mutex real es la fila del cliente).
        List<Long> partidas = libro.idsDePartidasScoped(clienteId, comprobanteIds).stream().sorted().toList();
        libro.bloquearPartidasScoped(clienteId, partidas);
        // Lectura ACTUAL del imputado (FOR UPDATE): inmune a la foto de REPEATABLE READ.
        Map<Long, BigDecimal> imputado = new LinkedHashMap<>();
        for (FilaImputada fila : imputaciones.imputadoBloqueadoScoped(clienteId, comprobanteIds)) {
            imputado.merge(fila.getComprobanteId(), fila.getImporte(), BigDecimal::add);
        }
        PartidasAbiertas partidasAbiertas = new PartidasAbiertas(debitosDe(debitos), imputado);
        List<PartidasAbiertas.Partida> excesos = partidasAbiertas.excesos(pedidas.stream()
                .collect(Collectors.toMap(Imputar::comprobanteId, Imputar::importe, (a, b) -> a, LinkedHashMap::new)));
        if (!excesos.isEmpty()) {
            throw new ImputacionInvalidaException(ImputacionInvalidaException.Motivo.EXCEDE_DEUDA, excesos.stream()
                    .map(p -> new ImputacionInvalidaException.DeudaActual(p.comprobanteId(), p.referencia(), p.pendiente()))
                    .toList());
        }
        imputaciones.saveAll(pedidas.stream()
                .map(p -> Imputacion.de(ferreteriaId, clienteId, asiento.reciboId(), p.comprobanteId(), p.importe()))
                .toList());
        Map<Long, String> referencias = debitos.stream()
                .collect(Collectors.toMap(DebitoDeComprobante::getComprobanteId, DebitoDeComprobante::getReferencia));
        return pedidas.stream()
                .map(p -> new ImputacionAsentada(p.comprobanteId(), referencias.get(p.comprobanteId()), p.importe()))
                .toList();
    }

    private static List<PartidasAbiertas.Debito> debitosDe(List<DebitoDeComprobante> debitos) {
        return debitos.stream()
                .map(d -> new PartidasAbiertas.Debito(d.getComprobanteId(), d.getPrimerMovimientoId(), d.getReferencia(),
                        d.getFecha(), d.getDebe()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImputacionAsentada> imputacionesDeRecibo(Long reciboId) {
        List<Imputacion> delRecibo = imputaciones.deReciboScoped(reciboId);
        if (delRecibo.isEmpty()) {
            return List.of();
        }
        Long clienteId = delRecibo.get(0).getClienteId();
        Map<Long, String> referencias = libro
                .debitosDeComprobantesScoped(clienteId, delRecibo.stream().map(Imputacion::getComprobanteId).toList()).stream()
                .collect(Collectors.toMap(DebitoDeComprobante::getComprobanteId, DebitoDeComprobante::getReferencia));
        return delRecibo.stream()
                .map(i -> new ImputacionAsentada(i.getComprobanteId(), referencias.get(i.getComprobanteId()), i.getImporte()))
                .toList();
    }

    /**
     * Los comprobantes con deuda del cliente, FIFO, con su saldo y lo que tiene «a cuenta» (CC-039). Es un consejo para
     * cobrar: UNA transacción de solo lectura = UNA foto consistente, sin lock (no toma el turno de ninguna cobranza; la
     * verdad se decide bajo lock en {@link #asentarCobro}). Un cliente desactivado se consulta igual.
     */
    @Transactional(readOnly = true)
    public PendientesCuenta pendientes(Long clienteId) {
        Cliente cliente = clientes.findScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
        List<DebitoDeComprobante> debitos = libro.debitosDeClienteScoped(clienteId);
        Map<Long, BigDecimal> imputado = imputaciones.imputadoPorComprobanteScoped(clienteId).stream()
                .collect(Collectors.toMap(ImputadoDeComprobante::getComprobanteId, ImputadoDeComprobante::getImputado));
        BigDecimal aCuenta = libro.creditosDeCobroScoped(clienteId).subtract(imputaciones.totalImputadoScoped(clienteId));
        List<PendientesCuenta.Pendiente> pendientes = new PartidasAbiertas(debitosDe(debitos), imputado).abiertas().stream()
                .map(p -> new PendientesCuenta.Pendiente(p.comprobanteId(), p.referencia(), p.fecha(), p.importe(),
                        p.imputado(), p.pendiente()))
                .toList();
        return new PendientesCuenta(cliente.getSaldo(), aCuenta, pendientes);
    }

    private CuentaBloqueada cuentaBloqueada(Long clienteId) {
        return clientes.bloquearCuentaScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }
}
