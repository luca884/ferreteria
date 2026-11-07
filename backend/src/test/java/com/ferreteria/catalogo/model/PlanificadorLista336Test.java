package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.ListasReales;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El planificador con el libro tal como lo deja el lector, armado con los fixtures que copian la estructura de las
 * listas reales (obs #5378): LISTA 336 (varias hojas, moneda por fila, IVA derivado de sin IVA / con IVA, titulos en
 * Mon.) y M-S (.xls de encabezado de dos filas, titulos Familia: NNN, costos con 4 decimales).
 */
class PlanificadorLista336Test {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** Codigo A, descripcion B, moneda E, costo F, sin IVA H y con IVA I; datos desde la fila 2 de las hojas elegidas. */
    private static MapeoLista mapeo336(List<Integer> hojas) {
        return new MapeoLista(0, 2, 0, 1, 5, null, hojas, 4, null, 7, 8, null, null, null, null, null, null, null);
    }

    private static PlanImportacion planificar(LecturaLista lectura, MapeoLista mapeo, List<ArticuloVigente> articulos) {
        FilaLeida encabezado = lectura.filas().stream()
            .filter(f -> f.hoja() == mapeo.hoja() && f.numero() == mapeo.filaDesde() - 1)
            .findFirst().orElse(null);
        ContextoPrecio contexto = new ContextoPrecio(null, bd("30.00"), bd("10"), bd("1000"));
        return PlanificadorImportacion.planificar(new EntradaPlan(
            lectura.filas(), mapeo, false, Bonificaciones.NINGUNA, articulos, Map.of(), contexto, new HashSet<>(), null,
            encabezado, lectura.hojas(), true, Map.of(), new HashSet<>()));
    }

    private static List<String> codigos(List<AltaPlaneada> altas) {
        return altas.stream().map(AltaPlaneada::codigo).toList();
    }

    @Test
    @DisplayName("LISTA 336 chica como primera importacion: no hay suben, bajan ni 'no estan'; los nuevos son las filas con importe y sin error")
    void primeraImportacion() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0, 1, 2)), List.of());

        assertThat(codigos(plan.altas())).containsExactly(
            "TOR-001", "TOR-002", "TOR-003", "TOR-004", "TUE-001", "TUE-002", "ARA-001");
        assertThat(plan.cambios()).isEmpty();
        assertThat(plan.noEstan()).isEmpty();
        assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 7, 0, 0, 1, 0, 0, 1));
    }

    @Test
    @DisplayName("el titulo ALEBA CARGADORES suma a salteadas y la fila con costo 0 es el unico error, con su hoja")
    void titulosYCostoCero() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0, 1, 2)), List.of());

        assertThat(plan.salteadas()).isEqualTo(1);
        assertThat(plan.errores()).containsExactly(new AvisoDeFila(0, 7, "TOR-005",
            "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.", "Tornillo sin precio"));
    }

    @Test
    @DisplayName("38 subrubros repetidos en las 9 columnas son 38 salteadas y los 7 costos 0 son los unicos errores (no 45)")
    void subrubrosRepetidosNoSonErrores() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        List<List<Object>> ezeta = new ArrayList<>();
        ezeta.add(Arrays.asList("Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "Sin IVA",
            "Con IVA"));
        int errores = 0;
        for (int i = 1; i <= 38; i++) {
            String subrubro = i % 2 == 0 ? "SUBRUBRO " + i : "Subrubro " + i + " ";
            ezeta.add(Arrays.asList(subrubro, subrubro, subrubro, subrubro, subrubro, subrubro, subrubro, subrubro,
                subrubro.toUpperCase()));
            ezeta.add(Arrays.asList("E-" + i, "Articulo " + i, null, 10, "ARG", 100 * i, 1, 100 * i, 121 * i));
            if (i % 5 == 0) {
                errores++;
                ezeta.add(Arrays.asList("Z-" + i, "Sin precio " + i, null, 10, "ARG", 0, 1, 0, 0));
            }
        }
        for (int i = 1; i <= 7 - errores; i++) {
            ezeta.add(Arrays.asList("Y-" + i, "Sin precio " + i, null, 10, "ARG", 0, 1, 0, 0));
        }
        hojas.put("EZETA", ezeta);

        PlanImportacion plan = planificar(ListasReales.leida(hojas), mapeo336(List.of(0)), List.of());

        assertThat(plan.salteadas()).isEqualTo(38);
        assertThat(plan.errores()).hasSize(7);
        assertThat(plan.errores()).extracting(AvisoDeFila::mensaje)
            .containsOnly("El costo calculado es 0. Revisá el precio de lista y las bonificaciones.");
        assertThat(plan.altas()).hasSize(38);
        assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 38, 0, 0, 7, 0, 0, 38));
    }

    @Test
    @DisplayName("el costo es F (por unidad), nunca H (por bulto): el articulo con bulto 10 queda con costo 150")
    void elCostoEsPorUnidad() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0)), List.of());

        AltaPlaneada porBulto = plan.altas().stream().filter(a -> a.codigo().equals("TOR-004")).findFirst().orElseThrow();
        assertThat(porBulto.costo()).isEqualByComparingTo("150.00");
        assertThat(porBulto.precioLista()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("el IVA sale de I / H: las filas al 10,5 quedan en IVA_10_5 y las demas en IVA_21")
    void ivaDerivado() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0, 1, 2)), List.of());

        assertThat(plan.altas()).filteredOn(a -> a.alicuotaIva() == AlicuotaIva.IVA_10_5)
            .extracting(AltaPlaneada::codigo).containsExactly("TOR-003");
        assertThat(plan.altas()).filteredOn(a -> a.alicuotaIva() == AlicuotaIva.IVA_21).hasSize(6);
        assertThat(plan.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("las filas ARG quedan en pesos y las USD en dolares, con el precio calculado con el dolar vigente")
    void monedaPorFila() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0, 1, 2)), List.of());

        assertThat(plan.altas()).filteredOn(a -> a.moneda() == Moneda.USD)
            .extracting(AltaPlaneada::codigo).containsExactly("TOR-002", "TUE-002");
        AltaPlaneada importado = plan.altas().get(1);
        assertThat(importado.costo()).isEqualByComparingTo("10.00");
        // 10 x 1.000 x 1,30 x 1,21 = 15.730
        assertThat(importado.precioNuevo()).isEqualByComparingTo("15730");
        assertThat(plan.altas().get(0).moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("una moneda vacia o EUR es un error de esa fila y no frena a las demas")
    void monedaMala() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(
            Arrays.asList("Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "Sin IVA", "Con IVA"),
            Arrays.asList("A-1", "Con euro", null, 10, "EUR", 100, 1, 100, 121),
            Arrays.asList("A-2", "Sin moneda", null, 10, null, 100, 1, 100, 121),
            Arrays.asList("A-3", "Bien", null, 10, "ARG", 100, 1, 100, 121)));

        PlanImportacion plan = planificar(ListasReales.leida(hojas), mapeo336(List.of(0)), List.of());

        assertThat(plan.errores()).extracting(AvisoDeFila::codigo, AvisoDeFila::mensaje).containsExactly(
            org.assertj.core.api.Assertions.tuple("A-1", "Moneda no reconocida: «EUR»"),
            org.assertj.core.api.Assertions.tuple("A-2", "Falta la moneda"));
        assertThat(codigos(plan.altas())).containsExactly("A-3");
    }

    @Test
    @DisplayName("una fila con un IVA de columna que no es legal no frena a las demas")
    void ivaMaloNoFrena() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(
            Arrays.asList("Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "% IVA"),
            Arrays.asList("A-1", "IVA raro", null, 10, "ARG", 100, 1, 19),
            Arrays.asList("A-2", "IVA bien", null, 10, "ARG", 100, 1, 10.5)));
        MapeoLista conIva = new MapeoLista(0, 2, 0, 1, 5, null, null, 4, null, null, null, 7, null, null, null, null,
            null, null);

        PlanImportacion plan = planificar(ListasReales.leida(hojas), conIva, List.of());

        assertThat(plan.errores()).extracting(AvisoDeFila::codigo).containsExactly("A-1");
        assertThat(plan.altas()).extracting(AltaPlaneada::alicuotaIva).containsExactly(AlicuotaIva.IVA_10_5);
    }

    @Test
    @DisplayName("los articulos de la lista que ya existen se clasifican contra el libro entero: cambia de moneda, corrige IVA y sin cambio")
    void existentesContraElLibro() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());
        List<ArticuloVigente> articulos = List.of(
            // TOR-002 esta en pesos con 1.000 y la lista lo trae en USD: cambia de moneda.
            new ArticuloVigente(1L, "I1", "TOR-002", "x", bd("1000"), bd("1580"), null, null, AlicuotaIva.IVA_21, true),
            // TOR-003 es 10,5 en la lista y esta en IVA_21 con el mismo costo: corrige. 1.000 x 1,30 x 1,21 = 1.573.
            new ArticuloVigente(2L, "I2", "TOR-003", "x", bd("1000"), bd("1580"), null, null, AlicuotaIva.IVA_21, true),
            // TUE-001 es igual en todo.
            new ArticuloVigente(3L, "I3", "TUE-001", "x", bd("500"), bd("790"), null, null, AlicuotaIva.IVA_21, true),
            // Un articulo que no esta en ninguna hoja.
            new ArticuloVigente(4L, "I4", "Z-999", "x", bd("1"), bd("2"), null, null, AlicuotaIva.IVA_21, true));

        PlanImportacion plan = planificar(lectura, mapeo336(List.of(0, 1, 2)), articulos);

        assertThat(plan.cambiosDeMoneda()).extracting(CambioPlaneado::articuloId).containsExactly(1L);
        assertThat(plan.correcciones()).extracting(CambioPlaneado::articuloId).containsExactly(2L);
        assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(3L);
        assertThat(plan.noEstan()).extracting(ArticuloVigente::codigoProveedor).containsExactly("Z-999");
        assertThat(plan.conteos().cambianMoneda()).isEqualTo(1);
        assertThat(plan.conteos().corrigen()).isEqualTo(1);
    }

    // ---- M-S ----

    private static LecturaLista msLeida() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("M-S ", ListasReales.msChicaFilas());
        return ListasReales.leida(hojas);
    }

    /** M-S: A codigo, B descripcion, C precio por unidad; datos desde la fila 15. */
    private static MapeoLista mapeoMs() {
        return new MapeoLista(0, 15, 0, 1, 2, null);
    }

    @Test
    @DisplayName("M-S chica (.xls): los titulos Familia: NNN suman a salteadas y las filas de datos son altas")
    void msTitulosSalteados() {
        PlanImportacion plan = planificar(msLeida(), mapeoMs(), List.of());

        assertThat(plan.salteadas()).isEqualTo(2);
        assertThat(plan.errores()).isEmpty();
        assertThat(plan.altas()).hasSize(ListasReales.MS_CHICA_ARTICULOS);
        assertThat(codigos(plan.altas())).startsWith("P-001", "P-002", "P-003", "B-001");
    }

    @Test
    @DisplayName("M-S: los costos de 4 decimales entran con el costo a 2 decimales, HALF_UP")
    void msCostosA2Decimales() {
        PlanImportacion plan = planificar(msLeida(), mapeoMs(), List.of());

        assertThat(plan.altas().get(0).precioLista()).isEqualByComparingTo("1532.4361");
        assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("1532.44");
        assertThat(plan.altas().get(0).costo().scale()).isEqualTo(2);
        // 120,0049 -> 120,00 y 88,8888 -> 88,89
        assertThat(plan.altas().get(6).costo()).isEqualByComparingTo("120.00");
        assertThat(plan.altas().get(5).costo()).isEqualByComparingTo("88.89");
    }

    @Test
    @DisplayName("un archivo de una sola hoja sin columnas nuevas da los mismos contadores que antes")
    void unaHojaSinColumnasNuevas() {
        MapeoLista viejo = new MapeoLista(0, 2, 0, 1, 5, null);

        PlanImportacion plan = planificar(ListasReales.leida(ListasReales.lista336ChicaHojas()), viejo, List.of());

        // Solo la hoja 0: TOR-001 a TOR-004 son altas en pesos con IVA_21 y TOR-005 es costo 0; el titulo en Mon. no
        // se reconoce como titulo sin la columna de moneda y se ignora.
        assertThat(codigos(plan.altas())).containsExactly("TOR-001", "TOR-002", "TOR-003", "TOR-004");
        assertThat(plan.altas()).extracting(AltaPlaneada::moneda).containsOnly(Moneda.ARS);
        assertThat(plan.altas()).extracting(AltaPlaneada::alicuotaIva).containsOnly(AlicuotaIva.IVA_21);
        assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 4, 0, 0, 1, 0, 0, 0));
        List<Integer> filasConError = new ArrayList<>();
        plan.errores().forEach(e -> filasConError.add(e.fila()));
        assertThat(filasConError).containsExactly(7);
    }
}
