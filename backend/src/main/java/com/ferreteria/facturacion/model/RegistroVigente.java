package com.ferreteria.facturacion.model;

import java.time.Instant;

/**
 * Lo que el reintento del Dueño necesita del registro vigente de una venta, leído sin cargar la entidad en el contexto de
 * persistencia de quien llama (V36): así, después de reemplazarlo, ninguna copia vieja queda ahí.
 *
 * @param id el registro
 * @param estado su estado
 * @param registradoEn la hora de la que sale su CbteFch
 * @param clase FACTURA o NOTA_CREDITO
 * @param asociadoId en una nota de crédito, el registro de la factura que anula (V38); null en una factura
 */
public record RegistroVigente(long id, EstadoFiscal estado, Instant registradoEn, ClaseComprobante clase, Long asociadoId) {
}
