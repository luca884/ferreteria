package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ConteosImportacion;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Los contadores que el cliente vio en la vista previa y ahora confirma: los de lo elegido (suben, bajan, nuevos, sin
 * cambio, cambian de moneda y corrigen) y los del archivo (no estan, con error y salteadas). Los tres ultimos del corte 2
 * son opcionales: un cliente viejo que no los manda los da por cero.
 */
public record ConteosRequest(
        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer suben,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer bajan,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer nuevos,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer sinCambio,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer noEstan,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer conError,

        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer cambianMoneda,

        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer corrigen,

        @Min(value = 0, message = "Los contadores de la vista previa no pueden ser negativos.")
        Integer salteadas) {

    /** Los seis contadores de antes del corte 2; los nuevos quedan sin dato (cero). */
    public ConteosRequest(Integer suben, Integer bajan, Integer nuevos, Integer sinCambio, Integer noEstan,
                          Integer conError) {
        this(suben, bajan, nuevos, sinCambio, noEstan, conError, null, null, null);
    }

    public ConteosImportacion toConteos() {
        return new ConteosImportacion(suben, bajan, nuevos, sinCambio, noEstan, conError,
                cambianMoneda == null ? 0 : cambianMoneda, corrigen == null ? 0 : corrigen,
                salteadas == null ? 0 : salteadas);
    }
}
