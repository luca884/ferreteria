package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los tipos del plan de importacion (T-124): llevan la hoja, la moneda, la alicuota y los ajustes, y los
 * constructores de antes del corte 2 siguen funcionando con valores neutros.
 */
class PlanImportacionTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static final Ajustes MENOS_CINCO = new Ajustes(bd("-5"), null, null);

    // ------------------------------------------------------------------ ArticuloVigente

    @Test
    @DisplayName("ArticuloVigente trae la alicuota y los ajustes actuales del articulo")
    void articuloVigenteConAjustes() {
        ArticuloVigente vigente = new ArticuloVigente(1L, "INT-1", "A-1", "Tornillo", bd("950.00"), bd("1500.00"),
            null, null, AlicuotaIva.IVA_10_5, true, Moneda.USD, MENOS_CINCO);

        assertThat(vigente.alicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(vigente.ajustes()).isEqualTo(MENOS_CINCO);
        assertThat(vigente.moneda()).isEqualTo(Moneda.USD);
    }

    @Test
    @DisplayName("los constructores viejos de ArticuloVigente dejan los ajustes en NINGUNO (y la moneda en pesos)")
    void articuloVigenteConstructoresViejos() {
        ArticuloVigente enPesos = new ArticuloVigente(1L, "INT-1", "A-1", "x", bd("1"), bd("2"), null, null,
            AlicuotaIva.IVA_21, true);
        ArticuloVigente conMoneda = new ArticuloVigente(2L, "INT-2", "A-2", "x", bd("1"), bd("2"), null, null,
            AlicuotaIva.IVA_21, true, Moneda.USD);

        assertThat(enPesos.ajustes()).isEqualTo(Ajustes.NINGUNO);
        assertThat(enPesos.moneda()).isEqualTo(Moneda.ARS);
        assertThat(conMoneda.ajustes()).isEqualTo(Ajustes.NINGUNO);
        assertThat(conMoneda.moneda()).isEqualTo(Moneda.USD);
    }

    @Test
    @DisplayName("ArticuloVigente.de copia los ajustes guardados en el articulo")
    void articuloVigenteDesdeLaEntidad() {
        Articulo articulo = new Articulo(3L);
        articulo.actualizar(new DatosArticulo("INT-1", null, "A-1", "Tornillo", null, null, bd("950"), null,
            AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true),
            new ContextoPrecio(null, bd("30"), bd("10")));
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), null));

        ArticuloVigente vigente = ArticuloVigente.de(articulo);

        assertThat(vigente.ajustes()).isEqualTo(new Ajustes(bd("-5.00"), bd("3.00"), null));
    }

    // ------------------------------------------------------------------ EntradaPlan

    @Test
    @DisplayName("EntradaPlan trae los nombres de hoja, la bandera de la cotizacion, los rubros por nombre y las barras ocupadas")
    void entradaPlanCompleta() {
        EntradaPlan entrada = new EntradaPlan(List.of(), new MapeoLista(0, 2, 0, 1, 2, null), false,
            Bonificaciones.NINGUNA, List.of(), Map.of(), new ContextoPrecio(null, bd("30"), bd("10")), Set.of(), null,
            null, List.of("TORNILLOS", "TUERCAS"), false, Map.of("TORNILLERIA", 4L), Set.of("7791234567890"));

        assertThat(entrada.hojas()).containsExactly("TORNILLOS", "TUERCAS");
        assertThat(entrada.cotizacionActiva()).isFalse();
        assertThat(entrada.rubrosPorNombre()).containsEntry("TORNILLERIA", 4L);
        assertThat(entrada.codigosBarrasOcupados()).containsExactly("7791234567890");
    }

    @Test
    @DisplayName("el constructor viejo de EntradaPlan deja sin nombres de hoja, con la cotizacion activa y sin rubros ni barras")
    void entradaPlanConstructorViejo() {
        EntradaPlan entrada = new EntradaPlan(List.of(), new MapeoLista(0, 2, 0, 1, 2, null), false,
            Bonificaciones.NINGUNA, List.of(), Map.of(), new ContextoPrecio(null, bd("30"), bd("10")), Set.of(), null,
            null);

        assertThat(entrada.hojas()).isEmpty();
        assertThat(entrada.cotizacionActiva()).isTrue();
        assertThat(entrada.rubrosPorNombre()).isEmpty();
        assertThat(entrada.codigosBarrasOcupados()).isEmpty();
    }

    // ------------------------------------------------------------------ CambioPlaneado

    @Test
    @DisplayName("CambioPlaneado suma hoja, moneda anterior y nueva, alicuota anterior y nueva, y los ajustes")
    void cambioPlaneadoCompleto() {
        CambioPlaneado cambio = new CambioPlaneado(5, 7L, "A-1", "Tornillo", bd("10"), bd("1000.00"), bd("10.00"),
            bd("1580.00"), bd("15730.00"), true, false, 2, Moneda.ARS, Moneda.USD, AlicuotaIva.IVA_21,
            AlicuotaIva.IVA_10_5, Ajustes.NINGUNO, MENOS_CINCO);

        assertThat(cambio.hoja()).isEqualTo(2);
        assertThat(cambio.fila()).isEqualTo(5);
        assertThat(cambio.monedaAnterior()).isEqualTo(Moneda.ARS);
        assertThat(cambio.monedaNueva()).isEqualTo(Moneda.USD);
        assertThat(cambio.alicuotaAnterior()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(cambio.alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(cambio.ajustesAnteriores()).isEqualTo(Ajustes.NINGUNO);
        assertThat(cambio.ajustesNuevos()).isEqualTo(MENOS_CINCO);
    }

    @Test
    @DisplayName("el constructor viejo de CambioPlaneado deja la hoja en 0 y lo demas sin dato")
    void cambioPlaneadoConstructorViejo() {
        CambioPlaneado cambio = new CambioPlaneado(5, 7L, "A-1", "Tornillo", bd("10"), bd("1"), bd("2"), bd("3"),
            bd("4"), true, false);

        assertThat(cambio.hoja()).isZero();
        assertThat(cambio.monedaAnterior()).isNull();
        assertThat(cambio.monedaNueva()).isNull();
        assertThat(cambio.alicuotaAnterior()).isNull();
        assertThat(cambio.alicuotaNueva()).isNull();
        assertThat(cambio.ajustesNuevos()).isEqualTo(Ajustes.NINGUNO);
        assertThat(cambio.ajustesAnteriores()).isEqualTo(Ajustes.NINGUNO);
    }

    // ------------------------------------------------------------------ AltaPlaneada

    @Test
    @DisplayName("AltaPlaneada suma hoja, moneda, alicuota, ajustes, unidad, barras, rubro y precio")
    void altaPlaneadaCompleta() {
        AltaPlaneada alta = new AltaPlaneada(6, "TOL-1", "Tornillo", bd("10"), bd("10.00"), 1, Moneda.USD,
            AlicuotaIva.IVA_10_5, MENOS_CINCO, Unidad.METRO, "7791234567890", 4L, bd("15730.00"));

        assertThat(alta.hoja()).isEqualTo(1);
        assertThat(alta.moneda()).isEqualTo(Moneda.USD);
        assertThat(alta.alicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(alta.ajustes()).isEqualTo(MENOS_CINCO);
        assertThat(alta.unidad()).isEqualTo(Unidad.METRO);
        assertThat(alta.codigoBarras()).isEqualTo("7791234567890");
        assertThat(alta.rubroId()).isEqualTo(4L);
        assertThat(alta.precioNuevo()).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("el constructor viejo de AltaPlaneada deja pesos, IVA_21, sin ajustes, UNIDAD, sin barras ni rubro")
    void altaPlaneadaConstructorViejo() {
        AltaPlaneada alta = new AltaPlaneada(6, "TOL-1", "Tornillo", bd("10"), bd("10.00"));

        assertThat(alta.hoja()).isZero();
        assertThat(alta.moneda()).isEqualTo(Moneda.ARS);
        assertThat(alta.alicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(alta.ajustes()).isEqualTo(Ajustes.NINGUNO);
        assertThat(alta.unidad()).isEqualTo(Unidad.UNIDAD);
        assertThat(alta.codigoBarras()).isNull();
        assertThat(alta.rubroId()).isNull();
        assertThat(alta.precioNuevo()).isNull();
    }

    // ------------------------------------------------------------------ PrecioListaPlaneado

    @Test
    @DisplayName("PrecioListaPlaneado suma hoja, fila, codigo, descripcion, costo y lo que se corrige sin cambiar el precio")
    void precioListaPlaneadoCompleto() {
        PrecioListaPlaneado sinCambio = new PrecioListaPlaneado(9L, bd("1000"), 1, 12, "A-9", "Arandela", bd("1000.00"),
            AlicuotaIva.IVA_10_5, MENOS_CINCO);

        assertThat(sinCambio.hoja()).isEqualTo(1);
        assertThat(sinCambio.fila()).isEqualTo(12);
        assertThat(sinCambio.codigoProveedor()).isEqualTo("A-9");
        assertThat(sinCambio.descripcion()).isEqualTo("Arandela");
        assertThat(sinCambio.costo()).isEqualByComparingTo("1000");
        assertThat(sinCambio.alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(sinCambio.ajustesNuevos()).isEqualTo(MENOS_CINCO);
    }

    @Test
    @DisplayName("el constructor viejo de PrecioListaPlaneado solo lleva el articulo y el precio de lista")
    void precioListaPlaneadoConstructorViejo() {
        PrecioListaPlaneado sinCambio = new PrecioListaPlaneado(9L, bd("1000"));

        assertThat(sinCambio.articuloId()).isEqualTo(9L);
        assertThat(sinCambio.precioLista()).isEqualByComparingTo("1000");
        assertThat(sinCambio.hoja()).isZero();
        assertThat(sinCambio.alicuotaNueva()).isNull();
        assertThat(sinCambio.ajustesNuevos()).isNull();
    }

    // ------------------------------------------------------------------ PlanImportacion

    @Test
    @DisplayName("PlanImportacion separa suben y bajan, y trae cambios de moneda, correcciones y el contador de salteadas")
    void planCompleto() {
        CambioPlaneado sube = cambio(2, "100", "120");
        CambioPlaneado baja = cambio(3, "100", "80");
        CambioPlaneado moneda = cambio(4, "100", "10");
        CambioPlaneado corrige = cambio(5, "100", "100");
        ConteosImportacion conteos = new ConteosImportacion(1, 1, 0, 0, 0, 0, 1, 1, 42);

        PlanImportacion plan = new PlanImportacion(conteos, List.of(sube, baja), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(moneda), List.of(corrige), 42);

        assertThat(plan.suben()).containsExactly(sube);
        assertThat(plan.bajan()).containsExactly(baja);
        assertThat(plan.cambiosDeMoneda()).containsExactly(moneda);
        assertThat(plan.correcciones()).containsExactly(corrige);
        assertThat(plan.salteadas()).isEqualTo(42);
        assertThat(plan.conteos().salteadas()).isEqualTo(42);
    }

    @Test
    @DisplayName("el constructor viejo de PlanImportacion deja las listas nuevas vacias y sin salteadas")
    void planConstructorViejo() {
        PlanImportacion plan = new PlanImportacion(new ConteosImportacion(0, 0, 0, 0, 0, 0), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of());

        assertThat(plan.cambiosDeMoneda()).isEmpty();
        assertThat(plan.correcciones()).isEmpty();
        assertThat(plan.suben()).isEmpty();
        assertThat(plan.bajan()).isEmpty();
        assertThat(plan.salteadas()).isZero();
    }

    private static CambioPlaneado cambio(int fila, String costoAnterior, String costoNuevo) {
        return new CambioPlaneado(fila, (long) fila, "A-" + fila, "x", bd(costoNuevo), bd(costoAnterior),
            bd(costoNuevo), bd("1"), bd("2"), true, false);
    }
}
