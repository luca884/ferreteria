package com.ferreteria.ferreteria.service;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/** Lee y guarda los datos fiscales de la ferreteria del JWT, nunca de un parametro del cliente (CU-01 paso 1). */
@Service
public class DatosFiscalesService {

    /** Piso del inicio de actividades: corta errores de tipeo sin rechazar una ferreteria vieja real. */
    static final LocalDate INICIO_ACTIVIDADES_MINIMO = LocalDate.of(1900, 1, 1);

    private final FerreteriaRepository ferreteriaRepository;

    public DatosFiscalesService(FerreteriaRepository ferreteriaRepository) {
        this.ferreteriaRepository = ferreteriaRepository;
    }

    @Transactional(readOnly = true)
    public DatosFiscales actuales() {
        return DatosFiscales.de(ferreteriaDelToken());
    }

    @Transactional
    public DatosFiscales guardar(String razonSocial, CondicionIva condicionIva, String domicilioComercial,
            LocalDate inicioActividades, String ingresosBrutos) {
        if (inicioActividades != null && inicioActividades.isAfter(HoraArgentina.hoy())) {
            throw new DatosInvalidosException(
                    Map.of("inicioActividades", "El inicio de actividades no puede ser una fecha futura."));
        }
        if (inicioActividades != null && inicioActividades.isBefore(INICIO_ACTIVIDADES_MINIMO)) {
            throw new DatosInvalidosException(
                    Map.of("inicioActividades", "El inicio de actividades no puede ser anterior al 1/1/1900."));
        }
        Ferreteria ferreteria = ferreteriaDelToken();
        ferreteria.actualizarDatosFiscales(razonSocial, condicionIva, domicilioComercial, inicioActividades,
                ingresosBrutos);
        return DatosFiscales.de(ferreteria);
    }

    private Ferreteria ferreteriaDelToken() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        // Un token valido siempre tiene su ferreteria: si falta es un estado corrupto, no un 404.
        return ferreteriaRepository.findById(ferreteriaId)
                .orElseThrow(() -> new IllegalStateException("No existe la ferreteria " + ferreteriaId));
    }
}
