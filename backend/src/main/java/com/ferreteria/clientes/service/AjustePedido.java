package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.model.AjusteCuenta;
import com.ferreteria.clientes.model.TipoAjuste;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lo que pide la pantalla para ajustar la cuenta de un cliente (GDS-20): una clave de idempotencia, el tipo, el importe, el
 * motivo y, solo en un Descuento, a qué deudas se imputa. {@code tipo} viaja como texto para que uno inválido diga «Elegí...»
 * en vez del error de Jackson; lo valida {@link RegistroAjuste}. La ferretería y el usuario salen del JWT, no de acá.
 */
public record AjustePedido(String clave, Long clienteId, String tipo, BigDecimal importe, String motivo,
        List<Imputar> imputaciones) {

    /**
     * ¿Es el mismo ajuste que el ya registrado? Mismo cliente, tipo, importe (por valor, no por escala), motivo (recortado) y las
     * mismas imputaciones por deuda e importe, en cualquier orden. {@code asentadas} son las que pidió el Descuento original.
     */
    boolean mismoContenido(AjusteCuenta existente, List<ImputacionAsentada> asentadas) {
        return Objects.equals(clienteId, existente.getClienteId())
                && tipo != null && tipo.equals(existente.getTipo().name())
                && importe != null && importe.compareTo(existente.getImporte()) == 0
                && motivo != null && motivo.trim().equals(existente.getMotivo())
                && porDeuda(imputaciones.stream().map(i -> Map.entry(clave(i.comprobanteId(), i.ajusteId()), i.importe())).toList())
                        .equals(porDeuda(asentadas.stream()
                                .map(a -> Map.entry(clave(a.comprobanteId(), a.ajusteId()), a.importe())).toList()));
    }

    /** La deuda como clave: «C12» para un comprobante y «A7» para un Recargo (sus ids son espacios distintos). */
    private static String clave(Long comprobanteId, Long ajusteId) {
        return comprobanteId != null ? "C" + comprobanteId : "A" + ajusteId;
    }

    /** Importes por deuda, con la escala normalizada para que 10 y 10.00 sean iguales. */
    private static Map<String, BigDecimal> porDeuda(List<Map.Entry<String, BigDecimal>> renglones) {
        Map<String, BigDecimal> mapa = new HashMap<>();
        renglones.forEach(r -> mapa.merge(r.getKey(), r.getValue().stripTrailingZeros(), BigDecimal::add));
        mapa.replaceAll((k, v) -> v.stripTrailingZeros());
        return mapa;
    }

    /** El tipo ya validado. */
    TipoAjuste tipoAjuste() {
        return TipoAjuste.valueOf(tipo);
    }
}
