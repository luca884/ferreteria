package com.ferreteria.clientes.web;

import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.service.ExtractoCuenta.Extracto;
import com.ferreteria.common.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El extracto de la cuenta corriente de un cliente (CC-073, CC-077, CC-078), con el contrato plano del design §6: el
 * encabezado ({@code saldoInicial}, {@code totalDebe}, {@code totalHaber}, {@code saldoFinal}, {@code saldoActual}), el rango y el
 * tipo pedidos ({@code null} si no hubo), las líneas paginadas en {@code movimientos} (forma estable de {@link PageResponse}) y
 * {@code truncado}, solo verdadero en una impresión que superó el tope. El documento del cliente ({@code tipoDocumento},
 * {@code numeroDocumento}, nulos si no tiene) es para el impreso (CC-099). Todos los importes salen con 2 decimales.
 */
public record ExtractoResponse(Long clienteId, String clienteNombre, TipoDocumento tipoDocumento, String numeroDocumento,
        BigDecimal saldoActual, LocalDate desde, LocalDate hasta, TipoMovimientoCuenta tipo, BigDecimal saldoInicial, BigDecimal totalDebe, BigDecimal totalHaber, BigDecimal saldoFinal,
        PageResponse<LineaExtractoResponse> movimientos, boolean truncado) {

    public static ExtractoResponse de(Extracto extracto) {
        PageResponse<LineaExtractoResponse> movimientos = new PageResponse<>(
                extracto.lineas().stream().map(LineaExtractoResponse::de).toList(), extracto.page(), extracto.size(),
                extracto.totalLineas(), extracto.totalPaginas());
        return new ExtractoResponse(extracto.clienteId(), extracto.clienteNombre(), extracto.tipoDocumento(),
                extracto.numeroDocumento(), LineaExtractoResponse.dos(extracto.saldoActual()),
                extracto.desde(), extracto.hasta(), extracto.tipo(), LineaExtractoResponse.dos(extracto.saldoInicial()),
                LineaExtractoResponse.dos(extracto.totalDebe()), LineaExtractoResponse.dos(extracto.totalHaber()),
                LineaExtractoResponse.dos(extracto.saldoFinal()), movimientos, extracto.truncado());
    }
}
