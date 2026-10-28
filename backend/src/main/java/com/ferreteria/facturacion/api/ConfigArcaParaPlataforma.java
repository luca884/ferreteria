package com.ferreteria.facturacion.api;

import java.time.Instant;
import java.util.Map;

/**
 * Puerto de {@code facturacion} para el módulo {@code plataforma} (super admin): lee y cambia la marca «de prueba», el
 * ambiente de ARCA y la facturación habilitada de una ferretería (CU-01 paso 2, D3). Solo tipos del JDK: la plataforma no
 * conoce la entidad, el repositorio ni el servicio de facturación.
 *
 * <p>Opera por id de ferretería explícito; una ferretería inexistente sale como 404. Una regla rota sale como la excepción
 * de regla de negocio de la aplicación (422) con el mensaje para el super admin. La primera vez que el super admin toca una
 * ferretería nace su fila; sin fila, la ferretería está {@link ResumenArca#SIN_CONFIGURAR}.
 *
 * <p>Los tres {@code cambiar*} toman el lock exclusivo de la fila de la ferretería y lo sostienen hasta el fin de la
 * transacción: dos cambios a la vez se serializan y el segundo decide sobre lo que dejó el primero. Quien los llama dentro
 * de su transacción tiene que llamarlos ANTES de cualquier otra lectura (ver {@code FerreteriasAdminService}).
 */
public interface ConfigArcaParaPlataforma {

    /** El resumen de cada ferretería que tiene fila, por id; las que no aparecen están sin configurar. Sin N+1. */
    Map<Long, ResumenArca> resumenPorFerreteria();

    ResumenArca resumenDe(long ferreteriaId);

    /** Pone o saca la marca «de prueba». No se saca mientras use el simulador. */
    ResumenArca cambiarDePrueba(long ferreteriaId, boolean dePrueba);

    /**
     * Asigna el simulador ({@code true}, con el punto de venta 1) o lo quita ({@code false}). Homologación y producción no
     * se asignan acá: las fija el certificado (rama 5).
     */
    ResumenArca cambiarSimulador(long ferreteriaId, boolean simulador);

    /** Prende la facturación (exige ambiente, punto de venta y los datos de CU-01 completos) o la apaga (siempre). */
    ResumenArca cambiarFacturacionHabilitada(long ferreteriaId, boolean habilitada);

    /**
     * Lo que el super admin ve de la configuración de ARCA de una ferretería. {@code ambiente} es {@code SIMULADOR},
     * {@code HOMOLOGACION}, {@code PRODUCCION} o null; {@code habilitadaEn} es null mientras la facturación esté apagada.
     */
    record ResumenArca(boolean dePrueba, String ambiente, Integer puntoVenta, boolean facturacionHabilitada,
            Instant habilitadaEn) {

        public static final ResumenArca SIN_CONFIGURAR = new ResumenArca(false, null, null, false, null);
    }
}
