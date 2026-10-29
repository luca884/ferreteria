package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.arca.PruebaDeConexion.PasoPrueba;
import java.util.List;
import java.util.Objects;

/**
 * El resultado de «Probar conexión» (rama 6, C1, {@code POST /api/facturacion/probar-conexion}). Sin secretos: ni el token,
 * ni el sign, ni el CMS.
 *
 * @param ambiente SIMULADOR, HOMOLOGACION o PRODUCCION
 * @param ok si pasaron los cuatro pasos (levanta el bloqueo de C4)
 * @param aviso «Simulador: no se probó contra ARCA» en SIMULADOR; null en los demás
 * @param pasos los que se corrieron, en orden: el primero que falla es el último
 * @param ultimos el último número autorizado de cada factura de la ferretería; vacío si ULTIMOS_NUMEROS no dio OK
 */
public record PruebaConexion(String ambiente, boolean ok, String aviso, List<Paso> pasos, List<Ultimo> ultimos) {

    public PruebaConexion {
        Objects.requireNonNull(ambiente, "el ambiente");
        pasos = List.copyOf(pasos);
        ultimos = List.copyOf(ultimos);
    }

    /**
     * Un paso de la prueba.
     *
     * @param paso cuál
     * @param ok si dio bien
     * @param detalle lo que dijo ARCA, legible
     */
    public record Paso(PasoPrueba paso, boolean ok, String detalle) {}

    /**
     * El último número autorizado de un tipo de factura en el punto de venta.
     *
     * @param cbteTipo el {@code CbteTipo} de WSFEv1 (1, 6 u 11)
     * @param comprobante «Factura A», «Factura B» o «Factura C»
     * @param numero el {@code CbteNro}; 0 si todavía no hay ninguno
     */
    public record Ultimo(int cbteTipo, String comprobante, long numero) {}
}
