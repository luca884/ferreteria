package com.ferreteria.facturacion.credencial;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que el Dueño ve en «Facturación» (C1, {@code GET /api/facturacion/configuracion}): el ambiente, el punto de venta y si
 * lo puede cambiar, la credencial activa, el pedido pendiente y los avisos. Sin secretos (S7): ni la clave privada ni el
 * dato cifrado.
 *
 * @param ambiente SIMULADOR, HOMOLOGACION, PRODUCCION o null
 * @param motivoPuntoVentaTrabado por qué no se puede cambiar el punto de venta; null si se puede
 * @param simulador true con el simulador: se puede firmar el pedido con su CA
 * @param credencial la activa, o null
 * @param pedido el pendiente, o null
 * @param avisos el vencimiento cercano o pasado, la clave ilegible o el certificado de otra CUIT
 */
public record ConfiguracionFacturacion(String ambiente, Integer puntoVenta, boolean puntoVentaEditable,
        String motivoPuntoVentaTrabado, boolean facturacionHabilitada, boolean simulador, Credencial credencial,
        Pedido pedido, List<String> avisos) {

    /**
     * La credencial activa.
     *
     * @param cuit los 11 dígitos del serialNumber del certificado
     * @param emisor el nombre de la CA que lo firmó («Computadores Test», «Simulador de ARCA»)
     * @param vigenteDesde y {@code vigenteHasta}: las fechas de Argentina de la vigencia del certificado
     * @param huella el SHA-256 de la clave pública, de a dos dígitos con dos puntos
     * @param diasParaVencer días de hoy (Argentina) a {@code vigenteHasta}; negativo si venció
     */
    public record Credencial(String cuit, String emisor, String ambiente, LocalDate vigenteDesde, LocalDate vigenteHasta,
            String huella, EstadoCredencial estado, long diasParaVencer) {}

    /** El pedido de certificado que espera el .crt: cuándo se generó (UTC), su CUIT y la huella de su clave pública. */
    public record Pedido(Instant creadoEn, String cuit, String huella) {}
}
