package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.Cupo;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * La cuenta corriente de los clientes (CC-001 a CC-010). Es la ÚNICA clase que escribe el saldo del cliente y el libro (lo
 * vigila ModulosArchTest): el saldo es una caché que siempre vale Σ(debe − haber) del libro, y los dos se escriben juntos
 * en la transacción de la venta, bajo el lock de la fila del cliente.
 *
 * <p>Lee el estado SIEMPRE con {@code bloquearCuentaScoped} (lectura actual de escalares): ni {@code findScoped}, que
 * devolvería la instancia ya gestionada con el saldo de antes, ni un {@code SUM} del libro, que en REPEATABLE READ vería la
 * foto del inicio de la transacción. El saldo se escribe relativo ({@code saldo = saldo + delta}), nunca con un valor leído.
 */
@Service
public class CuentaCorrienteService implements CuentaCorrienteParaVenta {

    private static final String NO_ENCONTRADO = "Cliente no encontrado";

    private final ClienteRepository clientes;
    private final MovimientoCuentaClienteRepository libro;
    private final Clock reloj;

    public CuentaCorrienteService(ClienteRepository clientes, MovimientoCuentaClienteRepository libro, Clock reloj) {
        this.clientes = clientes;
        this.libro = libro;
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
        libro.saveAll(pagos.stream()
                .map(p -> MovimientoCuentaCliente.venta(ferreteriaId, asiento.clienteId(), asiento.comprobanteId(),
                        p.pagoId(), p.monto(), fecha, asiento.usuarioId(), asiento.autorizadoPor()))
                .toList());
        BigDecimal total = pagos.stream().map(CargoPago::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        int filas = clientes.sumarSaldoScoped(asiento.clienteId(), total);
        if (filas != 1) {
            // El libro ya está escrito: sin la fila del saldo divergirían. Es un estado ilegal (500 y rollback), no un 4xx.
            throw new IllegalStateException("El saldo del cliente " + asiento.clienteId()
                    + " no se actualizó (filas afectadas: " + filas + "): el libro y el saldo divergirían.");
        }
    }

    private CuentaBloqueada cuentaBloqueada(Long clienteId) {
        return clientes.bloquearCuentaScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }
}
