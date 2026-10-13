package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ConteosImportacion;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Los seis contadores que el cliente vio en la vista previa y ahora confirma. */
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
        Integer conError) {

    public ConteosImportacion toConteos() {
        return new ConteosImportacion(suben, bajan, nuevos, sinCambio, noEstan, conError);
    }
}
