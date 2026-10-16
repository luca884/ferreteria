package com.ferreteria.catalogo.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Aplica la seleccion de filas de la vista previa a un plan (R-123, R-125, R-126). Pura: no escribe nada.
 *
 * <p>Las comprobaciones van en este orden y la primera que falla corta:
 * <ol>
 *   <li>una clave que no es una fila de datos del archivo (hoja o fila que no existen, titulos, el encabezado)
 *       da {@link SeleccionInvalidaException} (400);</li>
 *   <li>los contadores de lo elegido (suben, bajan, cambian de moneda, corrigen, nuevos y sin cambio) mas los del
 *       archivo (no estan, con error y salteadas) contra los que el cliente vio dan {@link ListaCambioException}
 *       (409). Un cambio en una fila no elegida que no mueve esos contadores no frena nada;</li>
 *   <li>una clave elegida que es una fila con error da 400;</li>
 *   <li>si se paso una seleccion y no queda ninguna fila, 400 "Elegi al menos una fila".</li>
 * </ol>
 * {@code elegidas == null} equivale a todas las filas que se pueden aplicar (el cliente de CU-04, sin seleccion).
 */
public final class SeleccionDeFilas {

    private static final String LISTA_CAMBIO =
        "La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.";

    private SeleccionDeFilas() {
    }

    /** El tipo de cada fila de datos del plan. */
    private enum Tipo { SUBE, BAJA, MONEDA, CORRIGE, NUEVO, SIN_CAMBIO, ERROR }

    /**
     * @param plan     el plan completo del archivo
     * @param elegidas las filas elegidas, o null para todas las que se pueden aplicar
     * @param esperados los contadores que el cliente vio para lo elegido y para el archivo
     * @return el plan de lo elegido: sus listas son las de las filas elegidas y sus contadores, los de lo aplicado
     *         (los de no estan, con error y salteadas siguen siendo los del archivo)
     */
    public static PlanImportacion aplicar(PlanImportacion plan, List<ClaveFila> elegidas, ConteosImportacion esperados) {
        Map<ClaveFila, Tipo> tipos = tiposDelPlan(plan);

        Set<ClaveFila> claves = elegidas == null ? aplicables(tipos) : new LinkedHashSet<>(elegidas);
        for (ClaveFila clave : claves) {
            if (!tipos.containsKey(clave)) {
                throw new SeleccionInvalidaException("La fila " + clave.fila() + " de la hoja " + (clave.hoja() + 1)
                    + " no está en la lista. Volvé a generar la vista previa.");
            }
        }

        Predicate<ClaveFila> estaElegida = claves::contains;
        PlanImportacion aplicado = filtrar(plan, estaElegida);
        if (!aplicado.conteos().equals(esperados)) {
            throw new ListaCambioException(LISTA_CAMBIO);
        }

        for (ClaveFila clave : claves) {
            if (tipos.get(clave) == Tipo.ERROR) {
                throw new SeleccionInvalidaException("La fila " + clave.fila() + " de la hoja " + (clave.hoja() + 1)
                    + " tiene un error y no se puede aplicar.");
            }
        }
        if (elegidas != null && cantidadDeAplicadas(aplicado) == 0) {
            throw new SeleccionInvalidaException("Elegí al menos una fila.");
        }
        return aplicado;
    }

    private static Map<ClaveFila, Tipo> tiposDelPlan(PlanImportacion plan) {
        Map<ClaveFila, Tipo> tipos = new HashMap<>();
        for (CambioPlaneado c : plan.cambios()) {
            tipos.put(new ClaveFila(c.hoja(), c.fila()), c.costoNuevo().compareTo(c.costoAnterior()) > 0 ? Tipo.SUBE : Tipo.BAJA);
        }
        plan.cambiosDeMoneda().forEach(c -> tipos.put(new ClaveFila(c.hoja(), c.fila()), Tipo.MONEDA));
        plan.correcciones().forEach(c -> tipos.put(new ClaveFila(c.hoja(), c.fila()), Tipo.CORRIGE));
        plan.altas().forEach(a -> tipos.put(new ClaveFila(a.hoja(), a.fila()), Tipo.NUEVO));
        plan.sinCambio().forEach(s -> tipos.put(new ClaveFila(s.hoja(), s.fila()), Tipo.SIN_CAMBIO));
        for (AvisoDeFila e : plan.errores()) {
            if (e.fila() != null) {
                tipos.put(new ClaveFila(e.hoja() == null ? 0 : e.hoja(), e.fila()), Tipo.ERROR);
            }
        }
        return tipos;
    }

    private static Set<ClaveFila> aplicables(Map<ClaveFila, Tipo> tipos) {
        Set<ClaveFila> claves = new LinkedHashSet<>();
        tipos.forEach((clave, tipo) -> {
            if (tipo != Tipo.ERROR) {
                claves.add(clave);
            }
        });
        return claves;
    }

    private static PlanImportacion filtrar(PlanImportacion plan, Predicate<ClaveFila> elegida) {
        List<CambioPlaneado> cambios = filtrados(plan.cambios(), c -> elegida.test(new ClaveFila(c.hoja(), c.fila())));
        List<CambioPlaneado> monedas = filtrados(plan.cambiosDeMoneda(), c -> elegida.test(new ClaveFila(c.hoja(), c.fila())));
        List<CambioPlaneado> correcciones = filtrados(plan.correcciones(), c -> elegida.test(new ClaveFila(c.hoja(), c.fila())));
        List<AltaPlaneada> altas = filtrados(plan.altas(), a -> elegida.test(new ClaveFila(a.hoja(), a.fila())));
        List<PrecioListaPlaneado> sinCambio = filtrados(plan.sinCambio(), s -> elegida.test(new ClaveFila(s.hoja(), s.fila())));
        int suben = (int) cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) > 0).count();
        ConteosImportacion conteos = new ConteosImportacion(suben, cambios.size() - suben, altas.size(), sinCambio.size(),
            plan.conteos().noEstan(), plan.conteos().conError(), monedas.size(), correcciones.size(),
            plan.conteos().salteadas());
        return new PlanImportacion(conteos, cambios, altas, sinCambio, plan.errores(), plan.advertencias(),
            plan.noEstan(), monedas, correcciones, plan.salteadas());
    }

    private static int cantidadDeAplicadas(PlanImportacion aplicado) {
        return aplicado.cambios().size() + aplicado.cambiosDeMoneda().size() + aplicado.correcciones().size()
            + aplicado.altas().size() + aplicado.sinCambio().size();
    }

    private static <T> List<T> filtrados(List<T> lista, Predicate<T> criterio) {
        List<T> resultado = new ArrayList<>();
        for (T elemento : lista) {
            if (criterio.test(elemento)) {
                resultado.add(elemento);
            }
        }
        return resultado;
    }
}
