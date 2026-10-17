package com.ferreteria.cotizacion.web.dto;

import com.ferreteria.cotizacion.model.LadoCotizacion;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * El dolar en el que cotizan los precios de la ferreteria: su tipo, el lado, el valor vigente (el que fijo su ultimo
 * recalculo; vacio si todavia no hay ninguno) y desde cuando rige (vacio si nunca se fijo uno). {@code aConfirmar} es
 * el dolar nuevo que se aleja mas del 20 % del vigente y espera que el Dueno lo confirme (auditoria H09); vacio si no
 * hay ninguno. Va sin porcentaje ni mensaje: el menu lo ven todos los roles y solo marca el valor.
 */
public record PreciosResponse(String codigo, LadoCotizacion lado, BigDecimal valor, Instant desde,
                              BigDecimal aConfirmar) {}
