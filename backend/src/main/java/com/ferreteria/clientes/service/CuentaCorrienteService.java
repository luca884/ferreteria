package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.AjusteCuenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CreditoDisponible;
import com.ferreteria.clientes.model.Cupo;
import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.PartidasAbiertas;
import com.ferreteria.clientes.model.Referencias;
import com.ferreteria.clientes.model.TipoAjuste;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaDeImputacion;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputada;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputadaARecargo;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputadaDeCredito;
import com.ferreteria.clientes.repository.ImputacionRepository.ImputadoDeComprobante;
import com.ferreteria.clientes.repository.ImputacionRepository.ImputadoDeRecargo;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.DebitoDeComprobante;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.FilaDeCredito;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final String RECARGO_NO_ENCONTRADO = "Recargo no encontrado en la cuenta del cliente";

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
        // GDS-20: la deuda nueva consume sola el saldo a favor, del crédito más viejo al más nuevo, hasta lo que debe.
        consumirCredito(asiento.clienteId(), ferreteriaId, Imputacion.Destino.comprobante(asiento.comprobanteId()), total);
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
        List<ImputacionAsentada> asentadas = pedidas.isEmpty() ? List.of()
                : imputarContraPartidas(clienteId, Imputacion.Origen.recibo(asiento.reciboId()), pedidas, ferreteriaId);
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
        return validarImputaciones(asiento.imputaciones(), total, "cobro");
    }

    /**
     * Las imputaciones pedidas por un crédito de {@code total} (un recibo o un Descuento): cada una a exactamente un
     * comprobante o un Recargo, con importe positivo de hasta 2 decimales, sin repetir destino, y Σ ≤ total. Las devuelve
     * ordenadas: comprobantes por id y después Recargos por id (el orden de las imputaciones y de la respuesta).
     */
    private static List<Imputar> validarImputaciones(List<Imputar> pedidas, BigDecimal total, String credito) {
        if (pedidas == null) {
            throw new IllegalArgumentException("Las imputaciones del " + credito + " no pueden ser null (sin imputar es una lista vacía).");
        }
        Set<Long> comprobantesVistos = new HashSet<>();
        Set<Long> recargosVistos = new HashSet<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (Imputar imputar : pedidas) {
            if ((imputar.comprobanteId() == null) == (imputar.ajusteId() == null)) {
                throw new IllegalArgumentException("Cada imputación necesita exactamente un comprobante o un Recargo.");
            }
            BigDecimal importe = imputar.importe();
            Long destino = imputar.comprobanteId() != null ? imputar.comprobanteId() : imputar.ajusteId();
            if (importe == null || importe.signum() <= 0 || importe.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("El importe a imputar a " + destino
                        + " tiene que ser mayor que cero y de hasta 2 decimales.");
            }
            if (!(imputar.comprobanteId() != null ? comprobantesVistos : recargosVistos).add(destino)) {
                throw new IllegalArgumentException("La deuda " + destino + " viene dos veces.");
            }
            suma = suma.add(importe);
        }
        if (suma.compareTo(total) > 0) {
            throw new IllegalArgumentException("Lo imputado supera el total del " + credito + ".");
        }
        return pedidas.stream()
                .sorted(Comparator.comparing((Imputar i) -> i.comprobanteId() == null)
                        .thenComparing(i -> i.comprobanteId() != null ? i.comprobanteId() : i.ajusteId()))
                .toList();
    }

    /**
     * Imputa un crédito (el recibo o el Descuento que se está asentando, con su {@code origen}) contra las deudas pedidas
     * (ya ordenadas): valida contra los pendientes ACTUALES bajo lock y guarda una imputación por deuda. Los pasos del diseño
     * §4: débitos (lectura plana, monótona), ids de las partidas del libro (de comprobantes y de Recargos juntos) bloqueados por
     * PK ascendente, imputado con {@code FOR UPDATE}, excesos → {@link ImputacionInvalidaException}.
     */
    private List<ImputacionAsentada> imputarContraPartidas(Long clienteId, Imputacion.Origen origen, List<Imputar> pedidas,
            Long ferreteriaId) {
        List<Long> comprobanteIds = pedidas.stream().map(Imputar::comprobanteId).filter(Objects::nonNull).toList(); // ya ascendentes
        List<Long> recargoIds = pedidas.stream().map(Imputar::ajusteId).filter(Objects::nonNull).toList(); // ya ascendentes
        List<DebitoDeComprobante> debitos = new ArrayList<>();
        if (!comprobanteIds.isEmpty()) {
            debitos.addAll(libro.debitosDeComprobantesScoped(clienteId, comprobanteIds));
            Set<Long> conDebito = debitos.stream().map(DebitoDeComprobante::getComprobanteId).collect(Collectors.toSet());
            if (!conDebito.containsAll(comprobanteIds)) {
                throw new ResourceNotFoundException(COMPROBANTE_NO_ENCONTRADO);
            }
        }
        if (!recargoIds.isEmpty()) {
            List<DebitoDeComprobante> delosRecargos = libro.debitosDeRecargosScoped(clienteId, recargoIds);
            Set<Long> conDebito = delosRecargos.stream().map(DebitoDeComprobante::getAjusteId).collect(Collectors.toSet());
            if (!conDebito.containsAll(recargoIds)) {
                throw new ResourceNotFoundException(RECARGO_NO_ENCONTRADO);
            }
            debitos.addAll(delosRecargos);
        }
        // Orden de locks: partidas por PK ascendente (defensa en profundidad; el mutex real es la fila del cliente).
        List<Long> partidas = new ArrayList<>();
        if (!comprobanteIds.isEmpty()) {
            partidas.addAll(libro.idsDePartidasScoped(clienteId, comprobanteIds));
        }
        if (!recargoIds.isEmpty()) {
            partidas.addAll(libro.idsDePartidasDeRecargosScoped(clienteId, recargoIds));
        }
        libro.bloquearPartidasScoped(clienteId, partidas.stream().sorted().toList());
        // Lectura ACTUAL del imputado (FOR UPDATE): inmune a la foto de REPEATABLE READ.
        Map<Long, BigDecimal> imputadoAComprobantes = new LinkedHashMap<>();
        if (!comprobanteIds.isEmpty()) {
            for (FilaImputada fila : imputaciones.imputadoBloqueadoScoped(clienteId, comprobanteIds)) {
                imputadoAComprobantes.merge(fila.getComprobanteId(), fila.getImporte(), BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> imputadoARecargos = new LinkedHashMap<>();
        if (!recargoIds.isEmpty()) {
            for (FilaImputadaARecargo fila : imputaciones.imputadoBloqueadoDeRecargosScoped(clienteId, recargoIds)) {
                imputadoARecargos.merge(fila.getAjusteId(), fila.getImporte(), BigDecimal::add);
            }
        }
        PartidasAbiertas partidasAbiertas = new PartidasAbiertas(debitosDe(debitos), imputadoAComprobantes, imputadoARecargos);
        List<PartidasAbiertas.Partida> excesos = partidasAbiertas.excesos(
                pedidas.stream().filter(p -> p.comprobanteId() != null)
                        .collect(Collectors.toMap(Imputar::comprobanteId, Imputar::importe, (a, b) -> a, LinkedHashMap::new)),
                pedidas.stream().filter(p -> p.ajusteId() != null)
                        .collect(Collectors.toMap(Imputar::ajusteId, Imputar::importe, (a, b) -> a, LinkedHashMap::new)));
        if (!excesos.isEmpty()) {
            throw new ImputacionInvalidaException(ImputacionInvalidaException.Motivo.EXCEDE_DEUDA, excesos.stream()
                    .map(p -> new ImputacionInvalidaException.DeudaActual(p.comprobanteId(), p.ajusteId(), p.referencia(),
                            p.pendiente()))
                    .toList());
        }
        imputaciones.saveAll(pedidas.stream()
                .map(p -> Imputacion.de(ferreteriaId, clienteId, origen,
                        new Imputacion.Destino(p.comprobanteId(), p.ajusteId()), p.importe()))
                .toList());
        Map<Long, String> referenciasDeComprobantes = new LinkedHashMap<>();
        Map<Long, String> referenciasDeRecargos = new LinkedHashMap<>();
        for (DebitoDeComprobante debito : debitos) {
            if (debito.getComprobanteId() != null) {
                referenciasDeComprobantes.put(debito.getComprobanteId(), debito.getReferencia());
            } else {
                referenciasDeRecargos.put(debito.getAjusteId(), debito.getReferencia());
            }
        }
        return pedidas.stream()
                .map(p -> new ImputacionAsentada(p.comprobanteId(), p.ajusteId(),
                        p.comprobanteId() != null ? referenciasDeComprobantes.get(p.comprobanteId())
                                : referenciasDeRecargos.get(p.ajusteId()),
                        p.importe()))
                .toList();
    }

    private static List<PartidasAbiertas.Debito> debitosDe(List<DebitoDeComprobante> debitos) {
        return debitos.stream()
                .map(d -> new PartidasAbiertas.Debito(d.getComprobanteId(), d.getAjusteId(), d.getPrimerMovimientoId(),
                        d.getReferencia(), d.getFecha(), d.getDebe()))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ajustes manuales y consumo de crédito (GDS-20, G59)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Asienta un ajuste ya numerado y guardado ({@code RegistroAjuste} lo hizo bajo el lock del cliente y del numerador): una sola
     * puerta de escritura del libro, las imputaciones y el saldo, y se defiende sola: re-toma la fila del cliente.
     * <ol>
     *   <li>un Descuento imputa lo que pidió contra comprobantes y Recargos (los mismos pasos y la misma validación que el
     *       cobro, con el Descuento como origen); un Recargo no puede traer imputaciones;
     *   <li>un Recargo, deuda nueva, consume sola el crédito disponible (recibos y Descuentos sin imputar), del más viejo al más
     *       nuevo, hasta su importe;
     *   <li>guarda UN renglón {@code AJUSTE} (debe = importe si es Recargo, haber si es Descuento) y mueve el saldo con un UPDATE
     *       relativo.
     * </ol>
     * Sin chequeo de límite (como una anulación: es una corrección del Dueño) ni de activo (lo decide {@code RegistroAjuste}).
     *
     * @throws ImputacionInvalidaException si algún destino de un Descuento tiene menos pendiente que lo imputado
     * @throws IllegalArgumentException si los argumentos están mal formados (ajuste sin guardar, Recargo con imputaciones,
     *     Σ imputaciones &gt; importe del Descuento): {@code RegistroAjuste} ya los validó con 400
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AjusteAsentado asentarAjuste(AjusteCuenta ajuste, List<Imputar> pedidas) {
        if (ajuste.getId() == null) {
            throw new IllegalArgumentException("El ajuste tiene que estar guardado antes de asentarlo.");
        }
        if (ajuste.getTipo() == TipoAjuste.RECARGO && !pedidas.isEmpty()) {
            throw new IllegalArgumentException("Un Recargo no imputa: solo un Descuento.");
        }
        List<Imputar> ordenadas = validarImputaciones(pedidas, ajuste.getImporte(), "Descuento");
        Long clienteId = ajuste.getClienteId();
        CuentaBloqueada cuenta = cuentaBloqueada(clienteId); // ya la tiene tomada quien llama: acá se re-toma y se confirma
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        List<ImputacionAsentada> asentadas = ordenadas.isEmpty() ? List.of()
                : imputarContraPartidas(clienteId, Imputacion.Origen.ajuste(ajuste.getId()), ordenadas, ferreteriaId);
        boolean recargo = ajuste.getTipo() == TipoAjuste.RECARGO;
        if (recargo) {
            consumirCredito(clienteId, ferreteriaId, Imputacion.Destino.ajuste(ajuste.getId()), ajuste.getImporte());
        }
        MovimientoCuentaCliente guardado = libro.save(MovimientoCuentaCliente.ajuste(ferreteriaId, clienteId, ajuste.getId(),
                ajuste.getTipo(), ajuste.referencia(), ajuste.getImporte(), ajuste.getMotivo(), ajuste.getFecha(),
                ajuste.getUsuarioId()));
        BigDecimal delta = recargo ? ajuste.getImporte() : ajuste.getImporte().negate();
        int filas = clientes.sumarSaldoScoped(clienteId, delta);
        if (filas != 1) {
            // El libro ya está escrito: sin la fila del saldo divergirían. Es un estado ilegal (500 y rollback), no un 4xx.
            throw new IllegalStateException("El saldo del cliente " + clienteId
                    + " no se actualizó (filas afectadas: " + filas + "): el libro y el saldo divergirían.");
        }
        return new AjusteAsentado(guardado.getId(), cuenta.getSaldo().add(delta), asentadas);
    }

    /**
     * La deuda nueva paga con el crédito que el cliente tiene a cuenta (S4): lee el crédito disponible con lectura ACTUAL
     * ({@link #creditoDisponible}), reparte FIFO hasta {@code deuda} y guarda una imputación AUTOMÁTICA por origen. Sin crédito no
     * guarda nada. Siempre bajo el lock del cliente.
     */
    private void consumirCredito(Long clienteId, Long ferreteriaId, Imputacion.Destino destino, BigDecimal deuda) {
        List<CreditoDisponible.Aplicacion> aplicaciones = creditoDisponible(clienteId).aplicar(deuda);
        if (aplicaciones.isEmpty()) {
            return;
        }
        imputaciones.saveAll(aplicaciones.stream()
                .map(a -> Imputacion.de(ferreteriaId, clienteId, a.origen(), destino, a.importe(), true))
                .toList());
    }

    /**
     * Lo que cada recibo y cada Descuento del cliente tiene sin imputar. El importe neto de un recibo es su COBRO menos su
     * ANULACION (un cobro anulado ya no es crédito, CC-064); el de un Descuento es el haber de su AJUSTE. Lo imputado es el
     * vigente (la anulación del recibo ya dio de baja lo suyo). Ambas lecturas son bloqueantes ({@code FOR UPDATE}): lectura
     * actual, inmune a la foto de REPEATABLE READ.
     */
    private CreditoDisponible creditoDisponible(Long clienteId) {
        Map<Long, Long> movimientoDeRecibo = new LinkedHashMap<>();
        Map<Long, BigDecimal> importeDeRecibo = new LinkedHashMap<>();
        Map<Long, Long> movimientoDeDescuento = new LinkedHashMap<>();
        Map<Long, BigDecimal> importeDeDescuento = new LinkedHashMap<>();
        for (FilaDeCredito fila : libro.creditosBloqueadosScoped(clienteId)) {
            switch (TipoMovimientoCuenta.valueOf(fila.getTipo())) {
                case COBRO -> {
                    movimientoDeRecibo.putIfAbsent(fila.getReciboId(), fila.getId());
                    importeDeRecibo.merge(fila.getReciboId(), fila.getHaber(), BigDecimal::add);
                }
                case ANULACION -> importeDeRecibo.merge(fila.getReciboId(), fila.getDebe().negate(), BigDecimal::add);
                case AJUSTE -> {
                    movimientoDeDescuento.put(fila.getAjusteId(), fila.getId());
                    importeDeDescuento.merge(fila.getAjusteId(), fila.getHaber(), BigDecimal::add);
                }
                default -> throw new IllegalStateException("El libro devolvió un movimiento que no mueve crédito: " + fila.getTipo());
            }
        }
        Map<Long, BigDecimal> imputadoDeRecibo = new LinkedHashMap<>();
        Map<Long, BigDecimal> imputadoDeDescuento = new LinkedHashMap<>();
        for (FilaImputadaDeCredito fila : imputaciones.imputadoDeCreditosBloqueadoScoped(clienteId)) {
            if (fila.getReciboId() != null) {
                imputadoDeRecibo.merge(fila.getReciboId(), fila.getImporte(), BigDecimal::add);
            } else {
                imputadoDeDescuento.merge(fila.getAjusteOrigenId(), fila.getImporte(), BigDecimal::add);
            }
        }
        List<CreditoDisponible.Credito> creditos = new ArrayList<>();
        movimientoDeRecibo.forEach((reciboId, movimientoId) -> creditos.add(new CreditoDisponible.Credito(
                Imputacion.Origen.recibo(reciboId), movimientoId, importeDeRecibo.get(reciboId),
                imputadoDeRecibo.getOrDefault(reciboId, BigDecimal.ZERO))));
        movimientoDeDescuento.forEach((ajusteId, movimientoId) -> creditos.add(new CreditoDisponible.Credito(
                Imputacion.Origen.ajuste(ajusteId), movimientoId, importeDeDescuento.get(ajusteId),
                imputadoDeDescuento.getOrDefault(ajusteId, BigDecimal.ZERO))));
        return new CreditoDisponible(creditos);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Anulación de un cobro (CC-062, CC-063, CC-065, CC-068, CC-085, CC-102)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Asienta la anulación de un cobro (design §3, pasos a–g). Una sola puerta de escritura, y se defiende sola: no confía en
     * que ventas haya mirado el estado del recibo ni en la foto de la transacción.
     * <ol>
     *   <li>valida lo de programador y arma el contra-asiento (sin tocar la base);
     *   <li>re-toma la fila del cliente ({@code cuentaBloqueada}: no-op si ya la tiene) — NINGÚN SELECT plano antes;
     *   <li>los movimientos del recibo (plano, ya con el cliente en X): un solo COBRO del cliente con haber = total y ninguna
     *       ANULACION; si no, rompería I2b/I2e;
     *   <li>ids de las imputaciones del recibo (todas), {@code FOR UPDATE} por PK exacta en orden ascendente con lectura
     *       ACTUAL de su vigencia (una foto vieja no vería la baja que otra anulación confirmó) y baja de la vigencia: la
     *       ÚNICA mutación de imputaciones; debe afectar exactamente esas filas;
     *   <li>guarda UNA ANULACION (debe = total) y SUBE el saldo con un UPDATE relativo.
     * </ol>
     * Sin chequeo de límite (CC-065: anular puede dejar al cliente excedido) ni de activo (lo decide ventas).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AnulacionAsentada asentarAnulacion(AsientoAnulacion asiento) {
        MovimientoCuentaCliente contraAsiento = contraAsientoDe(asiento);
        Long clienteId = asiento.clienteId();
        cuentaBloqueada(clienteId); // ya la tiene tomada quien llama: acá se re-toma y se confirma que existe y es de la ferretería
        exigirCobroSinAnular(asiento);
        List<Long> comprobantesLiberados = darDeBajaLasImputacionesDelRecibo(clienteId, asiento.reciboId());
        MovimientoCuentaCliente guardado = libro.save(contraAsiento);
        int filas = clientes.sumarSaldoScoped(clienteId, asiento.total());
        if (filas != 1) {
            // El libro ya está escrito: sin la fila del saldo divergirían. Es un estado ilegal (500 y rollback), no un 4xx.
            throw new IllegalStateException("El saldo del cliente " + clienteId
                    + " no se actualizó (filas afectadas: " + filas + "): el libro y el saldo divergirían.");
        }
        return new AnulacionAsentada(guardado.getId(), comprobantesLiberados);
    }

    /** Lo de programador (ventas ya validó con 400): ids, número, total positivo de hasta 2 decimales y motivo. */
    private MovimientoCuentaCliente contraAsientoDe(AsientoAnulacion asiento) {
        if (asiento.clienteId() == null || asiento.reciboId() == null) {
            throw new IllegalArgumentException("La anulación necesita el cliente y el recibo.");
        }
        String referencia = Referencias.anulacion(asiento.reciboNumero()); // número no positivo → IllegalArgumentException
        BigDecimal total = asiento.total();
        if (total == null || total.signum() <= 0 || total.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El total de la anulación tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        return MovimientoCuentaCliente.anulacion(TenantContext.requireFerreteriaId(), asiento.clienteId(), asiento.reciboId(),
                referencia, total, asiento.motivo(), LocalDateTime.now(reloj), asiento.usuarioId());
    }

    /** Exactamente un COBRO del cliente con haber = total y ninguna ANULACION del recibo (plano, ya con el cliente tomado). */
    private void exigirCobroSinAnular(AsientoAnulacion asiento) {
        List<MovimientoCuentaCliente> delRecibo = libro.deReciboScoped(asiento.reciboId());
        if (delRecibo.stream().anyMatch(m -> m.getTipo() == TipoMovimientoCuenta.ANULACION)) {
            throw new IllegalStateException("El recibo " + asiento.reciboId() + " ya tiene su ANULACION en el libro.");
        }
        List<MovimientoCuentaCliente> cobros = delRecibo.stream().filter(m -> m.getTipo() == TipoMovimientoCuenta.COBRO).toList();
        boolean unCobroDelCliente = cobros.size() == 1 && cobros.get(0).getClienteId().equals(asiento.clienteId())
                && cobros.get(0).getHaber().compareTo(asiento.total()) == 0;
        if (!unCobroDelCliente) {
            throw new IllegalStateException("El recibo " + asiento.reciboId() + " no tiene un único COBRO del cliente "
                    + asiento.clienteId() + " por " + asiento.total() + ": no se puede anular.");
        }
    }

    /**
     * Bloquea por PK ascendente y da de baja TODAS las imputaciones del recibo, también las automáticas que le sacaron crédito
     * a una venta o a un Recargo posteriores (esas deudas vuelven a quedar pendientes). Devuelve los comprobantes que quedan
     * pendientes (distinct, ascendentes). Sin imputaciones (adelanto) no toca nada.
     */
    private List<Long> darDeBajaLasImputacionesDelRecibo(Long clienteId, Long reciboId) {
        List<Long> ids = imputaciones.idsDeReciboScoped(reciboId).stream().sorted().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<FilaDeImputacion> bloqueadas = imputaciones.bloquearDeReciboScoped(clienteId, ids); // lectura ACTUAL
        if (bloqueadas.size() != ids.size()) {
            throw new IllegalStateException("Se esperaban " + ids.size() + " imputaciones del recibo " + reciboId
                    + " y el bloqueo devolvió " + bloqueadas.size() + ".");
        }
        if (bloqueadas.stream().anyMatch(fila -> !fila.getVigente())) {
            throw new IllegalStateException("El recibo " + reciboId + " tiene imputaciones que ya no son vigentes: ya se anuló.");
        }
        int desactivadas = imputaciones.desactivarDeReciboScoped(clienteId, reciboId, ids);
        if (desactivadas != ids.size()) {
            throw new IllegalStateException("Se dieron de baja " + desactivadas + " imputaciones del recibo " + reciboId
                    + " y se esperaban " + ids.size() + ".");
        }
        // GDS-20: la imputación de un recibo puede ir a un Recargo (sin comprobante): esos no figuran entre los comprobantes liberados.
        return bloqueadas.stream().map(FilaDeImputacion::getComprobanteId).filter(Objects::nonNull).distinct().sorted().toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImputacionAsentada> imputacionesDeRecibo(Long reciboId) {
        List<Imputacion> delRecibo = imputaciones.deReciboScoped(reciboId);
        if (delRecibo.isEmpty()) {
            return List.of();
        }
        return asentadasDe(delRecibo.get(0).getClienteId(), delRecibo);
    }

    /**
     * Un ajuste ya asentado, tal como quedó (GDS-20): el saldo del cliente justo después de su renglón del libro y las
     * imputaciones que pidió el Descuento. Para responder una clave repetida. Lectura.
     */
    @Transactional(readOnly = true)
    public AjusteAsentado ajusteAsentado(AjusteCuenta ajuste) {
        List<MovimientoCuentaCliente> renglones = libro.deAjusteScoped(ajuste.getId());
        if (renglones.size() != 1) {
            throw new IllegalStateException("El ajuste " + ajuste.getId() + " tiene " + renglones.size()
                    + " renglones en el libro y se esperaba uno.");
        }
        Long movimientoId = renglones.get(0).getId();
        List<Imputacion> pedidas = imputaciones.pedidasPorDescuentoScoped(ajuste.getId());
        return new AjusteAsentado(movimientoId, libro.saldoHastaMovimientoScoped(ajuste.getClienteId(), movimientoId),
                pedidas.isEmpty() ? List.of() : asentadasDe(ajuste.getClienteId(), pedidas));
    }

    /** Las imputaciones con la referencia («Venta N.º n» o «Recargo N.º n») de la deuda a la que fueron, en el mismo orden. */
    private List<ImputacionAsentada> asentadasDe(Long clienteId, List<Imputacion> guardadas) {
        Map<Long, String> referenciasDeComprobantes = new LinkedHashMap<>();
        List<Long> comprobanteIds = guardadas.stream().map(Imputacion::getComprobanteId).filter(Objects::nonNull).toList();
        if (!comprobanteIds.isEmpty()) {
            libro.debitosDeComprobantesScoped(clienteId, comprobanteIds)
                    .forEach(d -> referenciasDeComprobantes.put(d.getComprobanteId(), d.getReferencia()));
        }
        Map<Long, String> referenciasDeRecargos = new LinkedHashMap<>();
        List<Long> recargoIds = guardadas.stream().map(Imputacion::getAjusteDestinoId).filter(Objects::nonNull).toList();
        if (!recargoIds.isEmpty()) {
            libro.debitosDeRecargosScoped(clienteId, recargoIds)
                    .forEach(d -> referenciasDeRecargos.put(d.getAjusteId(), d.getReferencia()));
        }
        return guardadas.stream()
                .map(i -> new ImputacionAsentada(i.getComprobanteId(), i.getAjusteDestinoId(),
                        i.getComprobanteId() != null ? referenciasDeComprobantes.get(i.getComprobanteId())
                                : referenciasDeRecargos.get(i.getAjusteDestinoId()),
                        i.getImporte()))
                .toList();
    }

    /**
     * Las deudas del cliente (comprobantes y Recargos), FIFO, con su saldo y lo que tiene «a cuenta» (CC-039). Es un consejo
     * para cobrar: UNA transacción de solo lectura = UNA foto consistente, sin lock (no toma el turno de ninguna cobranza; la
     * verdad se decide bajo lock en {@link #asentarCobro}). Un cliente desactivado se consulta igual.
     */
    @Transactional(readOnly = true)
    public PendientesCuenta pendientes(Long clienteId) {
        Cliente cliente = clientes.findScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
        List<DebitoDeComprobante> debitos = libro.debitosDeClienteScoped(clienteId);
        Map<Long, BigDecimal> imputadoAComprobantes = imputaciones.imputadoPorComprobanteScoped(clienteId).stream()
                .collect(Collectors.toMap(ImputadoDeComprobante::getComprobanteId, ImputadoDeComprobante::getImputado));
        Map<Long, BigDecimal> imputadoARecargos = imputaciones.imputadoPorRecargoScoped(clienteId).stream()
                .collect(Collectors.toMap(ImputadoDeRecargo::getAjusteId, ImputadoDeRecargo::getImputado));
        // crédito (COBRO − ANULACION + Descuentos) − imputado VIGENTE: un cobro anulado ya no es crédito ni resta de ninguna deuda
        BigDecimal aCuenta = libro.creditoNetoDeRecibosScoped(clienteId).add(libro.creditoDeDescuentosScoped(clienteId))
                .subtract(imputaciones.totalImputadoScoped(clienteId));
        List<PendientesCuenta.Pendiente> pendientes = new PartidasAbiertas(debitosDe(debitos), imputadoAComprobantes,
                imputadoARecargos).abiertas().stream()
                .map(p -> new PendientesCuenta.Pendiente(p.comprobanteId(), p.ajusteId(), p.referencia(), p.fecha(),
                        p.importe(), p.imputado(), p.pendiente()))
                .toList();
        return new PendientesCuenta(cliente.getSaldo(), aCuenta, pendientes);
    }

    private CuentaBloqueada cuentaBloqueada(Long clienteId) {
        return clientes.bloquearCuentaScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }
}
