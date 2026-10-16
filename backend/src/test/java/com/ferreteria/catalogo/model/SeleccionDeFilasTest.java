package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seleccion de filas de la vista previa (R-123, R-125, R-126). Orden de las comprobaciones: (1) una clave que no es
 * una fila de datos del archivo da 400, (2) los contadores de lo elegido y los del archivo contra los esperados dan
 * 409, (3) una clave que es una fila con error da 400 y (4) ninguna fila elegida da 400.
 */
class SeleccionDeFilasTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static CambioPlaneado cambio(int hoja, int fila, String costoAnterior, String costoNuevo, Moneda anterior,
                                         Moneda nueva) {
        return new CambioPlaneado(fila, (long) hoja * 100_000 + fila, "C-" + hoja + "-" + fila, "x", bd(costoNuevo),
            bd(costoAnterior), bd(costoNuevo), bd("1"), bd("2"), true, false, hoja, anterior, nueva, AlicuotaIva.IVA_21,
            AlicuotaIva.IVA_21, Ajustes.NINGUNO, Ajustes.NINGUNO);
    }

    private static CambioPlaneado sube(int hoja, int fila) {
        return cambio(hoja, fila, "100", "120", Moneda.ARS, Moneda.ARS);
    }

    private static CambioPlaneado baja(int hoja, int fila) {
        return cambio(hoja, fila, "100", "80", Moneda.ARS, Moneda.ARS);
    }

    private static CambioPlaneado deMoneda(int hoja, int fila) {
        return cambio(hoja, fila, "100", "10", Moneda.ARS, Moneda.USD);
    }

    private static CambioPlaneado corrige(int hoja, int fila) {
        return cambio(hoja, fila, "100", "100", Moneda.ARS, Moneda.ARS);
    }

    private static AltaPlaneada alta(int hoja, int fila) {
        return new AltaPlaneada(fila, "N-" + hoja + "-" + fila, "Nuevo", bd("10"), bd("10.00"), hoja, Moneda.ARS,
            AlicuotaIva.IVA_21, Ajustes.NINGUNO, Unidad.UNIDAD, null, null, bd("20"));
    }

    private static PrecioListaPlaneado sinCambio(int hoja, int fila) {
        return new PrecioListaPlaneado((long) hoja * 100_000 + fila, bd("100"), hoja, fila, "S-" + hoja + "-" + fila,
            "x", bd("100.00"), null, null);
    }

    private static AvisoDeFila error(int hoja, int fila) {
        return new AvisoDeFila(hoja, fila, "E-" + hoja + "-" + fila, "El costo está vacío o no es un número.");
    }

    /** Un plan con los contadores que le corresponden, mas los 'no estan' y las salteadas del archivo. */
    private static PlanImportacion plan(List<CambioPlaneado> cambios, List<CambioPlaneado> monedas,
                                        List<CambioPlaneado> correcciones, List<AltaPlaneada> altas,
                                        List<PrecioListaPlaneado> sinCambio, List<AvisoDeFila> errores, int noEstan,
                                        int salteadas) {
        List<ArticuloVigente> ausentes = new ArrayList<>();
        for (int i = 0; i < noEstan; i++) {
            ausentes.add(new ArticuloVigente((long) -i - 1, "I", "Z-" + i, "x", bd("1"), bd("2"), null, null,
                AlicuotaIva.IVA_21, true));
        }
        int suben = (int) cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) > 0).count();
        int bajan = cambios.size() - suben;
        ConteosImportacion conteos = new ConteosImportacion(suben, bajan, altas.size(), sinCambio.size(), noEstan,
            errores.size(), monedas.size(), correcciones.size(), salteadas);
        return new PlanImportacion(conteos, cambios, altas, sinCambio, errores, List.of(), ausentes, monedas,
            correcciones, salteadas);
    }

    /** 2 suben, 1 baja, 1 cambia de moneda, 1 corrige, 2 nuevos, 1 sin cambio y 1 error, en dos hojas; 3 'no estan' y 4 salteadas. */
    private static PlanImportacion planChico() {
        return plan(
            List.of(sube(0, 2), sube(1, 2), baja(0, 3)),
            List.of(deMoneda(0, 4)),
            List.of(corrige(1, 3)),
            List.of(alta(0, 5), alta(1, 4)),
            List.of(sinCambio(0, 6)),
            List.of(error(1, 5)),
            3, 4);
    }

    private static ConteosImportacion esperados(int suben, int bajan, int nuevos, int sinCambio, int moneda,
                                                int corrigen) {
        return new ConteosImportacion(suben, bajan, nuevos, sinCambio, 3, 1, moneda, corrigen, 4);
    }

    private static List<ClaveFila> claves(int... hojaYFila) {
        List<ClaveFila> claves = new ArrayList<>();
        for (int i = 0; i < hojaYFila.length; i += 2) {
            claves.add(new ClaveFila(hojaYFila[i], hojaYFila[i + 1]));
        }
        return claves;
    }

    // ------------------------------------------------------------------ todas o algunas

    @Test
    @DisplayName("elegidas null equivale a todas las elegibles: el plan queda igual, sin las filas con error como elegibles")
    void nullEsTodas() {
        PlanImportacion plan = planChico();

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(plan, null, esperados(2, 1, 2, 1, 1, 1));

        assertThat(aplicado.conteos()).isEqualTo(plan.conteos());
        assertThat(aplicado.cambios()).hasSize(3);
        assertThat(aplicado.cambiosDeMoneda()).hasSize(1);
        assertThat(aplicado.correcciones()).hasSize(1);
        assertThat(aplicado.altas()).hasSize(2);
        assertThat(aplicado.sinCambio()).hasSize(1);
    }

    @Test
    @DisplayName("una seleccion parcial devuelve solo lo elegido, con los contadores de lo aplicado y los del archivo")
    void seleccionParcial() {
        // Se eligen: la que sube de la hoja 1, la que baja, la nueva de la hoja 0 y la que corrige.
        List<ClaveFila> elegidas = claves(1, 2, 0, 3, 0, 5, 1, 3);

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(planChico(), elegidas, esperados(1, 1, 1, 0, 0, 1));

        assertThat(aplicado.cambios()).extracting(CambioPlaneado::hoja, CambioPlaneado::fila)
            .containsExactlyInAnyOrder(org.assertj.core.api.Assertions.tuple(1, 2), org.assertj.core.api.Assertions.tuple(0, 3));
        assertThat(aplicado.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-0-5");
        assertThat(aplicado.correcciones()).hasSize(1);
        assertThat(aplicado.cambiosDeMoneda()).isEmpty();
        assertThat(aplicado.sinCambio()).isEmpty();
        assertThat(aplicado.conteos()).isEqualTo(esperados(1, 1, 1, 0, 0, 1));
    }

    @Test
    @DisplayName("3.000 elegidas con 200 que suben, 2.700 nuevas y 100 sin cambio: el registro dice suben 200, nuevos 2.700 y sin cambio 100")
    void contadoresDeLoAplicado() {
        List<CambioPlaneado> suben = new ArrayList<>();
        List<AltaPlaneada> altas = new ArrayList<>();
        List<PrecioListaPlaneado> iguales = new ArrayList<>();
        List<ClaveFila> elegidas = new ArrayList<>();
        for (int i = 2; i < 202; i++) {
            suben.add(sube(0, i));
            elegidas.add(new ClaveFila(0, i));
        }
        for (int i = 300; i < 3000; i++) {
            altas.add(alta(1, i));
            elegidas.add(new ClaveFila(1, i));
        }
        for (int i = 4000; i < 4100; i++) {
            iguales.add(sinCambio(2, i));
            elegidas.add(new ClaveFila(2, i));
        }
        // Mas filas en el archivo que no se eligen.
        for (int i = 202; i < 1202; i++) {
            suben.add(sube(0, i));
        }
        PlanImportacion plan = plan(suben, List.of(), List.of(), altas, iguales, List.of(), 0, 0);

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(plan, elegidas,
            new ConteosImportacion(200, 0, 2700, 100, 0, 0, 0, 0, 0));

        assertThat(aplicado.conteos().suben()).isEqualTo(200);
        assertThat(aplicado.conteos().nuevos()).isEqualTo(2700);
        assertThat(aplicado.conteos().sinCambio()).isEqualTo(100);
        assertThat(aplicado.cambios()).hasSize(200);
    }

    @Test
    @DisplayName("con 20.000 filas elegidas la comprobacion es instantanea")
    void veinteMilFilas() {
        List<AltaPlaneada> altas = new ArrayList<>();
        List<ClaveFila> elegidas = new ArrayList<>();
        for (int i = 2; i < 20_002; i++) {
            altas.add(alta(i % 70, i));
            elegidas.add(new ClaveFila(i % 70, i));
        }
        PlanImportacion plan = plan(List.of(), List.of(), List.of(), altas, List.of(), List.of(), 0, 0);

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(plan, elegidas,
            new ConteosImportacion(0, 0, 20_000, 0, 0, 0, 0, 0, 0));

        assertThat(aplicado.altas()).hasSize(20_000);
    }

    // ------------------------------------------------------------------ orden de las comprobaciones

    @Test
    @DisplayName("(1) una clave que no es fila de datos del archivo da 400, aunque los contadores tambien esten mal")
    void primeroLasClavesInexistentes() {
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), claves(0, 4000), esperados(9, 9, 9, 9, 9, 9)))
            .isInstanceOf(SeleccionInvalidaException.class)
            .hasMessageContaining("no está en la lista");
    }

    @Test
    @DisplayName("la fila 4.000 de una hoja que no existe en el archivo da 400")
    void hojaInexistente() {
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), claves(7, 4000), esperados(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(SeleccionInvalidaException.class);
    }

    @Test
    @DisplayName("una fila salteada o de titulo no es elegible: su clave no existe entre las filas de datos")
    void lasSalteadasNoSonElegibles() {
        // Las salteadas no tienen detalle ni clave: la fila 1 (encabezado) o un titulo no estan en el plan.
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), claves(0, 1), esperados(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(SeleccionInvalidaException.class);
    }

    @Test
    @DisplayName("(2) los contadores de lo elegido distintos de los esperados dan 409, antes que una clave con error")
    void despuesLaDeriva() {
        // Se elige una fila con error y los contadores esperados no coinciden con lo elegido.
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), claves(1, 5, 0, 2), esperados(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(ListaCambioException.class)
            .hasMessageContaining("La lista cambió desde la vista previa");
    }

    @Test
    @DisplayName("(3) una clave elegida que es una fila con error da 400 cuando los contadores coinciden")
    void despuesLasFilasConError() {
        // Elegida la que sube de la hoja 0 y la fila con error; los contadores de lo elegido son 1 sube.
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), claves(0, 2, 1, 5), esperados(1, 0, 0, 0, 0, 0)))
            .isInstanceOf(SeleccionInvalidaException.class)
            .hasMessageContaining("tiene un error");
    }

    @Test
    @DisplayName("(4) ninguna fila elegida da 400 'Elegí al menos una fila'")
    void ningunaFila() {
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(planChico(), List.of(), esperados(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(SeleccionInvalidaException.class)
            .hasMessage("Elegí al menos una fila.");
    }

    // ------------------------------------------------------------------ deriva

    @Test
    @DisplayName("un cambio en una fila NO elegida que no altera los contadores de lo elegido no da 409")
    void filaNoElegidaNoFrena() {
        // El plan de ahora tiene una fila mas que sube y la que baja ya no baja: nada de eso se eligio.
        PlanImportacion ahora = plan(
            List.of(sube(0, 2), sube(1, 2), sube(0, 9)),
            List.of(deMoneda(0, 4)), List.of(corrige(1, 3)), List.of(alta(0, 5), alta(1, 4)),
            List.of(sinCambio(0, 6), sinCambio(0, 3)), List.of(error(1, 5)), 3, 4);

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(ahora, claves(0, 2, 0, 5), esperados(1, 0, 1, 0, 0, 0));

        assertThat(aplicado.cambios()).extracting(CambioPlaneado::fila).containsExactly(2);
        assertThat(aplicado.altas()).hasSize(1);
    }

    @Test
    @DisplayName("una fila elegida que paso de 'sube' a 'sin cambio' da 409")
    void filaElegidaCambioDeTipo() {
        PlanImportacion ahora = plan(
            List.of(sube(1, 2), baja(0, 3)), List.of(deMoneda(0, 4)), List.of(corrige(1, 3)),
            List.of(alta(0, 5), alta(1, 4)), List.of(sinCambio(0, 6), sinCambio(0, 2)), List.of(error(1, 5)), 3, 4);

        // Se vio la fila (0, 2) como 'sube'; ahora es 'sin cambio'.
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(ahora, claves(0, 2, 0, 5), esperados(1, 0, 1, 0, 0, 0)))
            .isInstanceOf(ListaCambioException.class);
    }

    @Test
    @DisplayName("los 'no estan' y las salteadas son del archivo, no de la seleccion: si cambian dan 409 aunque no se elija nada de ellos")
    void noEstanYSalteadasDelArchivo() {
        PlanImportacion ahora = plan(
            List.of(sube(0, 2), sube(1, 2), baja(0, 3)), List.of(deMoneda(0, 4)), List.of(corrige(1, 3)),
            List.of(alta(0, 5), alta(1, 4)), List.of(sinCambio(0, 6)), List.of(error(1, 5)), 4, 4);

        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(ahora, claves(0, 2), esperados(1, 0, 0, 0, 0, 0)))
            .isInstanceOf(ListaCambioException.class);

        PlanImportacion conOtrasSalteadas = plan(
            List.of(sube(0, 2), sube(1, 2), baja(0, 3)), List.of(deMoneda(0, 4)), List.of(corrige(1, 3)),
            List.of(alta(0, 5), alta(1, 4)), List.of(sinCambio(0, 6)), List.of(error(1, 5)), 3, 5);
        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(conOtrasSalteadas, claves(0, 2), esperados(1, 0, 0, 0, 0, 0)))
            .isInstanceOf(ListaCambioException.class);
    }

    @Test
    @DisplayName("la cantidad de filas con error del archivo tambien se compara")
    void erroresDelArchivo() {
        PlanImportacion ahora = plan(
            List.of(sube(0, 2), sube(1, 2), baja(0, 3)), List.of(deMoneda(0, 4)), List.of(corrige(1, 3)),
            List.of(alta(0, 5), alta(1, 4)), List.of(sinCambio(0, 6)), List.of(error(1, 5), error(1, 6)), 3, 4);

        assertThatThrownBy(() -> SeleccionDeFilas.aplicar(ahora, claves(0, 2), esperados(1, 0, 0, 0, 0, 0)))
            .isInstanceOf(ListaCambioException.class);
    }

    @Test
    @DisplayName("las claves repetidas cuentan una sola vez")
    void clavesRepetidas() {
        PlanImportacion aplicado = SeleccionDeFilas.aplicar(planChico(), claves(0, 2, 0, 2), esperados(1, 0, 0, 0, 0, 0));

        assertThat(aplicado.cambios()).hasSize(1);
    }

    @Test
    @DisplayName("sin selección y con un plan sin nada aplicable no es 400: es el comportamiento de CU-04")
    void nullSinNadaAplicable() {
        PlanImportacion soloErrores = plan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(error(0, 2)), 1, 0);

        PlanImportacion aplicado = SeleccionDeFilas.aplicar(soloErrores, null,
            new ConteosImportacion(0, 0, 0, 0, 1, 1, 0, 0, 0));

        assertThat(aplicado.conteos().conError()).isEqualTo(1);
        assertThat(aplicado.altas()).isEmpty();
    }
}
