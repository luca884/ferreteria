package com.ferreteria.clientes.api;

import java.util.Objects;

/**
 * Lo único de un cliente que ventas necesita: quién es (para copiar su nombre al documento), si se puede usar, si está
 * identificado (tiene DNI, CUIT o CUIL: lo pide la venta a Consumidor Final desde el tope, GDS-32) y sus datos fiscales,
 * que la venta facturada copia al receptor de la factura (CU-07, D4). Solo tipos del JDK y de este paquete.
 *
 * @param condicionIva la condición frente al IVA del cliente
 * @param tipoDocumento el documento, o null si no tiene
 * @param numeroDocumento solo dígitos, o null si no tiene documento
 * @param domicilio el domicilio, o null si no se cargó
 */
public record ClienteReferencia(Long id, String razonSocial, boolean activo, CondicionIva condicionIva,
        TipoDocumento tipoDocumento, String numeroDocumento, String domicilio) {

    public ClienteReferencia {
        Objects.requireNonNull(condicionIva, "Falta la condición frente al IVA del cliente.");
    }

    /** Tiene documento (DNI, CUIT o CUIL). Consumidor Final sin documento nunca lo está. */
    public boolean identificado() {
        return tipoDocumento != null;
    }

    /**
     * La condición frente al IVA del cliente como la ve quien usa este puerto. Es una copia de la del modelo de clientes,
     * que no sale del módulo.
     */
    public enum CondicionIva {
        CONSUMIDOR_FINAL,
        RESPONSABLE_INSCRIPTO,
        MONOTRIBUTISTA,
        EXENTO
    }

    /** El tipo de documento del cliente como lo ve quien usa este puerto (copia del del modelo de clientes). */
    public enum TipoDocumento {
        DNI,
        CUIT,
        CUIL
    }
}
