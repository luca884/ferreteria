package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Articulo existente que la lista toca: su costo sube o baja, cambia de moneda o se corrige (mismo costo, otro IVA u
 * otros ajustes). {@code precioNuevo} es el precio final calculado con el contexto del articulo (para mostrar);
 * {@code variacionFuerte} marca la advertencia de +-50 %. Las monedas, las alicuotas y los ajustes, anteriores y
 * nuevos, son los que dejaria aplicar la fila: lo que no cambia queda igual en los dos lados.
 *
 * @param recargosPorcentaje la suma de los % de los recargos del costo (CU-13); null si el articulo no tiene recargos
 * @param costoRealAnterior el costo real de hoy (el costo mas los recargos), en la moneda de hoy; null sin recargos
 * @param costoRealNuevo  el costo real con el costo de la lista, en la moneda nueva; null sin recargos
 */
public record CambioPlaneado(
        int fila,
        Long articuloId,
        String codigoProveedor,
        String descripcion,
        BigDecimal precioLista,
        BigDecimal costoAnterior,
        BigDecimal costoNuevo,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo,
        boolean activo,
        boolean variacionFuerte,
        int hoja,
        Moneda monedaAnterior,
        Moneda monedaNueva,
        AlicuotaIva alicuotaAnterior,
        AlicuotaIva alicuotaNueva,
        Ajustes ajustesAnteriores,
        Ajustes ajustesNuevos,
        BigDecimal recargosPorcentaje,
        BigDecimal costoRealAnterior,
        BigDecimal costoRealNuevo) {

    public CambioPlaneado {
        ajustesAnteriores = ajustesAnteriores == null ? Ajustes.NINGUNO : ajustesAnteriores;
        ajustesNuevos = ajustesNuevos == null ? Ajustes.NINGUNO : ajustesNuevos;
    }

    /** El cambio sin recargos del costo: los tres campos del costo real en null. */
    public CambioPlaneado(int fila, Long articuloId, String codigoProveedor, String descripcion,
                          BigDecimal precioLista, BigDecimal costoAnterior, BigDecimal costoNuevo,
                          BigDecimal precioAnterior, BigDecimal precioNuevo, boolean activo,
                          boolean variacionFuerte, int hoja, Moneda monedaAnterior, Moneda monedaNueva,
                          AlicuotaIva alicuotaAnterior, AlicuotaIva alicuotaNueva, Ajustes ajustesAnteriores,
                          Ajustes ajustesNuevos) {
        this(fila, articuloId, codigoProveedor, descripcion, precioLista, costoAnterior, costoNuevo, precioAnterior,
            precioNuevo, activo, variacionFuerte, hoja, monedaAnterior, monedaNueva, alicuotaAnterior, alicuotaNueva,
            ajustesAnteriores, ajustesNuevos, null, null, null);
    }

    /** El cambio de antes del corte 2: hoja 0 y sin dato de moneda, alicuota ni ajustes. */
    public CambioPlaneado(int fila, Long articuloId, String codigoProveedor, String descripcion,
                          BigDecimal precioLista, BigDecimal costoAnterior, BigDecimal costoNuevo,
                          BigDecimal precioAnterior, BigDecimal precioNuevo, boolean activo,
                          boolean variacionFuerte) {
        this(fila, articuloId, codigoProveedor, descripcion, precioLista, costoAnterior, costoNuevo, precioAnterior,
            precioNuevo, activo, variacionFuerte, 0, null, null, null, null, null, null);
    }

    public boolean cambiaLaMoneda() {
        return monedaAnterior != null && monedaNueva != null && monedaAnterior != monedaNueva;
    }
}
