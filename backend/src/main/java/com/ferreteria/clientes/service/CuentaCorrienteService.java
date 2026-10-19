package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.Cupo;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

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
        Cupo cupo = Cupo.de(cuenta.getLimiteCuentaCorriente(), cuenta.getSaldo(), importe);
        return new CupoCuenta(cupo.habilitada(), cuenta.getLimiteCuentaCorriente(), cuenta.getSaldo(), cupo.disponible(),
                cupo.exceso());
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
        clientes.sumarSaldoScoped(asiento.clienteId(), total);
    }

    private CuentaBloqueada cuentaBloqueada(Long clienteId) {
        return clientes.bloquearCuentaScoped(clienteId).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }
}
