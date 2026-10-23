package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.model.AjusteCuenta;
import com.ferreteria.clientes.model.TipoAjuste;
import com.ferreteria.clientes.repository.AjusteCuentaRepository;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ClienteRepository.CuentaBloqueada;
import com.ferreteria.clientes.repository.NumeradorAjusteCuentaRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra un ajuste manual de la cuenta corriente en UNA transacción (GDS-20, S6). Primero valida la forma sin tocar nada y
 * recién después escribe, en el mismo orden de locks que la venta, la cobranza y la anulación (el prefijo «cliente» es común, así
 * que nunca hay deadlock con ellas):
 * <pre>
 * CLIENTE (X, FOR UPDATE nativo) → [repetida: el PRIMER SELECT plano] → numerador_ajuste_cuenta (X) → ajuste_cuenta
 *   → partidas (por id asc, FOR UPDATE) → imputaciones y créditos del cliente (FOR UPDATE) → imputaciones nuevas → libro + saldo
 * </pre>
 * Las cuatro últimas líneas las hace {@link CuentaCorrienteService#asentarAjuste}. No usa la caja: ajustar una cuenta no mueve
 * efectivo, así que no exige caja abierta.
 *
 * <p><b>Regla estructural</b> (la misma de {@code RegistroCobranza}): NINGUNA lectura plana antes de tomar al cliente. La foto
 * REPEATABLE READ se fija con la primera lectura consistente; si algo plano corriera antes del lock, la foto sería anterior al
 * commit de la operación que se acaba de esperar y se sobre-imputaría o se consumiría crédito de más.
 *
 * <p>El que llama ({@link AjusteCuentaService}) no tiene transacción: resuelve la carrera de la clave y reintenta los deadlocks.
 * Si algo falla después del numerador, la transacción entera se revierte: el número no se consume (sin huecos).
 */
@Service
public class RegistroAjuste {

    static final String MENSAJE_OTRO_CONTENIDO = "Este ajuste ya se registró con otros datos";
    static final String MENSAJE_CLIENTE_INACTIVO = "El cliente está desactivado.";
    static final String MENSAJE_EXCEDE_DEUDA = "La deuda de alguna partida cambió. Revisá la imputación y confirmá de nuevo.";
    private static final String NO_ENCONTRADO = "Cliente no encontrado";
    /** El tope de DECIMAL(15,2): el mismo máximo que una venta o un cobro. */
    private static final BigDecimal IMPORTE_MAXIMO = new BigDecimal("9999999999999.99");
    private static final int MAX_IMPUTACIONES = 200;
    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ClienteRepository clientes;
    private final NumeradorAjusteCuentaRepository numerador;
    private final AjusteCuentaRepository ajustes;
    private final CuentaCorrienteService cuentaCorriente;
    private final Clock reloj;

    public RegistroAjuste(ClienteRepository clientes, NumeradorAjusteCuentaRepository numerador, AjusteCuentaRepository ajustes,
            CuentaCorrienteService cuentaCorriente, Clock reloj) {
        this.clientes = clientes;
        this.numerador = numerador;
        this.ajustes = ajustes;
        this.cuentaCorriente = cuentaCorriente;
        this.reloj = reloj;
    }

    /**
     * Registra el ajuste, o devuelve el que ya tiene esta clave (200 en vez de 201). Va en el orden del Javadoc de la clase.
     *
     * @throws DatosInvalidosException (400) si el pedido está mal formado, antes de tocar nada
     * @throws ResourceNotFoundException (404) cliente ajeno o inexistente; (404) deuda a imputar que no es de ese cliente
     * @throws ReglaDeNegocioException (422) cliente desactivado
     * @throws ConflictException (409) clave con otro contenido
     * @throws com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException (409) la deuda de alguna
     *     partida cambió: trae las deudas actuales
     */
    @Transactional
    public AjusteRegistrado registrar(AjustePedido pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        validarForma(pedido);

        // El cliente se TOMA acá (X), antes de cualquier lectura plana y de cualquier numerador. Ajeno o inexistente: 404.
        CuentaBloqueada cliente = clientes.bloquearCuentaScoped(pedido.clienteId())
                .orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
        if (!cliente.getActivo()) {
            throw new ReglaDeNegocioException(MENSAJE_CLIENTE_INACTIVO);
        }

        // Recién ahora el primer SELECT plano: con el cliente en X la foto ve todo lo que confirmó cualquier operación previa.
        Optional<AjusteRegistrado> yaRegistrado = buscarRepetida(pedido);
        if (yaRegistrado.isPresent()) {
            return yaRegistrado.get();
        }

        long numero = numerador.siguienteScoped();
        AjusteCuenta ajuste = ajustes.save(AjusteCuenta.nuevo(ferreteriaId, pedido.clienteId(), numero, pedido.tipoAjuste(),
                pedido.importe(), pedido.motivo(), pedido.clave(), LocalDateTime.now(reloj), usuarioId));
        // Si la deuda cambió (ImputacionInvalidaException) se revierte todo, ajuste y numerador incluidos.
        AjusteAsentado asentado = cuentaCorriente.asentarAjuste(ajuste, pedido.imputaciones());
        return new AjusteRegistrado(ajuste, asentado.saldo(), asentado.imputaciones(), false);
    }

    /**
     * El ajuste que ya tiene la clave del pedido en la ferretería del request, si lo hay. Solo lee y NO toma ningún lock: la
     * fachada la llama como pre-chequeo en una transacción propia (devuelve 200 aunque el cliente se haya desactivado) y después
     * de perder la carrera de la clave única. Con el mismo contenido es el ajuste original; con otro, 409.
     */
    @Transactional(readOnly = true)
    public Optional<AjusteRegistrado> repetida(AjustePedido pedido) {
        TenantContext.requireFerreteriaId();
        validarForma(pedido);
        return buscarRepetida(pedido);
    }

    private Optional<AjusteRegistrado> buscarRepetida(AjustePedido pedido) {
        Optional<AjusteCuenta> existente = ajustes.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        AjusteCuenta ajuste = existente.get();
        AjusteAsentado asentado = cuentaCorriente.ajusteAsentado(ajuste);
        if (!pedido.mismoContenido(ajuste, asentado.imputaciones())) {
            throw new ConflictException(MENSAJE_OTRO_CONTENIDO);
        }
        return Optional.of(new AjusteRegistrado(ajuste, asentado.saldo(), asentado.imputaciones(), true));
    }

    /**
     * Paso 0: la forma del pedido, sin base de datos, con todos los errores juntos (uno por campo). Un Recargo no se imputa; las
     * imputaciones de un Descuento son a una sola deuda cada una (comprobante XOR Recargo), sin repetir, y suman a lo sumo el
     * importe. Un error en {@code imputaciones} (repetida, suma mayor o Recargo imputado) gana el primero.
     */
    private static void validarForma(AjustePedido pedido) {
        Map<String, String> errores = new LinkedHashMap<>();
        if (pedido.clave() == null || !UUID.matcher(pedido.clave()).matches()) {
            errores.put("clave", "La clave del ajuste no es válida.");
        }
        TipoAjuste tipo = null;
        try {
            tipo = pedido.tipo() == null ? null : TipoAjuste.valueOf(pedido.tipo());
        } catch (IllegalArgumentException e) {
            // cae en el mensaje de abajo
        }
        if (tipo == null) {
            errores.put("tipo", "Elegí si es un Recargo o un Descuento.");
        }
        boolean importeValido = false;
        if (pedido.importe() == null || pedido.importe().signum() <= 0) {
            errores.put("importe", "El importe tiene que ser mayor que cero.");
        } else if (pedido.importe().stripTrailingZeros().scale() > 2) {
            errores.put("importe", "El importe admite hasta 2 decimales.");
        } else if (pedido.importe().compareTo(IMPORTE_MAXIMO) > 0) {
            errores.put("importe", "El importe supera el máximo permitido.");
        } else {
            importeValido = true;
        }
        String motivo = pedido.motivo() == null ? "" : pedido.motivo().trim();
        if (motivo.length() < 5 || motivo.length() > 200) {
            errores.put("motivo", "El motivo tiene que tener entre 5 y 200 caracteres.");
        }

        List<Imputar> renglones = pedido.imputaciones();
        if (tipo == TipoAjuste.RECARGO && !renglones.isEmpty()) {
            errores.put("imputaciones", "Un Recargo no se imputa: solo un Descuento.");
        } else if (renglones.size() > MAX_IMPUTACIONES) {
            errores.put("imputaciones", "Un Descuento admite hasta " + MAX_IMPUTACIONES + " imputaciones.");
        } else {
            validarImputaciones(pedido, renglones, importeValido, errores);
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    private static void validarImputaciones(AjustePedido pedido, List<Imputar> renglones, boolean importeValido,
            Map<String, String> errores) {
        BigDecimal suma = BigDecimal.ZERO;
        boolean renglonesValidos = true;
        boolean repetido = false;
        Set<Long> comprobantesVistos = new HashSet<>();
        Set<Long> recargosVistos = new HashSet<>();
        for (int i = 0; i < renglones.size(); i++) {
            Imputar renglon = renglones.get(i);
            if (renglon.comprobanteId() == null && renglon.ajusteId() == null) {
                errores.put("imputaciones[" + i + "].comprobanteId", "Elegí el comprobante o el Recargo.");
                renglonesValidos = false;
            } else if (renglon.comprobanteId() != null && renglon.ajusteId() != null) {
                errores.put("imputaciones[" + i + "].ajusteId", "Elegí el comprobante o el Recargo, no los dos.");
                renglonesValidos = false;
            } else if (!(renglon.comprobanteId() != null ? comprobantesVistos : recargosVistos)
                    .add(renglon.comprobanteId() != null ? renglon.comprobanteId() : renglon.ajusteId())) {
                repetido = true;
            }
            if (renglon.importe() == null || renglon.importe().signum() <= 0) {
                errores.put("imputaciones[" + i + "].importe", "El importe tiene que ser mayor que cero.");
                renglonesValidos = false;
            } else if (renglon.importe().stripTrailingZeros().scale() > 2) {
                errores.put("imputaciones[" + i + "].importe", "El importe admite hasta 2 decimales.");
                renglonesValidos = false;
            } else {
                suma = suma.add(renglon.importe());
            }
        }
        if (repetido) {
            errores.put("imputaciones", "Cada comprobante o Recargo va una sola vez.");
        } else if (importeValido && renglonesValidos && suma.compareTo(pedido.importe()) > 0) {
            errores.put("imputaciones", "Lo imputado supera el importe del Descuento.");
        }
    }
}
