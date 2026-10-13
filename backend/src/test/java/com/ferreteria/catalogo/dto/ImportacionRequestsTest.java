package com.ferreteria.catalogo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.MapeoLista;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Validacion de los pedidos de importacion (la parte "datos" del multipart), sin Spring. */
class ImportacionRequestsTest {

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void iniciar() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    /** Mapeo valido: hoja 0, datos desde la fila 2, codigo 0, descripcion 1, costo 2. */
    private static MapeoRequest mapeo() {
        return new MapeoRequest(0, 2, 0, 1, 2);
    }

    private static ConteosRequest conteos() {
        return new ConteosRequest(4, 2, 2, 1, 3, 1);
    }

    private static VistaPreviaRequest vistaPrevia(String b1, String b2, String b3) {
        return new VistaPreviaRequest(7L, mapeo(), false, bd(b1), bd(b2), bd(b3));
    }

    private static AplicarRequest aplicar(String b1, String b2, String b3) {
        return new AplicarRequest(7L, mapeo(), false, bd(b1), bd(b2), bd(b3), false, null, conteos());
    }

    /**
     * Campo (con ruta) a mensaje; si un campo tiene varios, queda el primero por orden alfabetico del mensaje.
     * Valida las dos pasadas: la del controlador (grupo por defecto) y la del servicio (bonificaciones).
     */
    private static Map<String, String> errores(Object pedido) {
        Set<ConstraintViolation<Object>> violaciones =
                validador.validate(pedido, Default.class, ValidacionBonificaciones.class);
        Map<String, String> errores = new TreeMap<>();
        for (ConstraintViolation<Object> v : violaciones) {
            errores.merge(v.getPropertyPath().toString(), v.getMessage(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return errores;
    }

    @Nested
    @DisplayName("MapeoRequest")
    class Mapeo {

        @Test
        @DisplayName("un mapeo completo es valido")
        void valido() {
            assertThat(errores(mapeo())).isEmpty();
        }

        @Test
        @DisplayName("la descripcion es opcional")
        void sinDescripcion() {
            assertThat(errores(new MapeoRequest(0, 2, 0, null, 2))).isEmpty();
        }

        @Test
        @DisplayName("hoja, primera fila, codigo y costo son obligatorios")
        void obligatorios() {
            Map<String, String> errores = errores(new MapeoRequest(null, null, null, null, null));

            assertThat(errores).containsOnlyKeys("hoja", "filaDesde", "columnaCodigo", "columnaCosto");
            assertThat(errores.get("columnaCodigo")).isEqualTo("Elegí la columna del código.");
            assertThat(errores.get("columnaCosto")).isEqualTo("Elegí la columna del costo.");
        }

        @Test
        @DisplayName("los indices no pueden ser negativos")
        void indicesNegativos() {
            Map<String, String> errores = errores(new MapeoRequest(-1, 2, -1, -2, -3));

            assertThat(errores).containsOnlyKeys("hoja", "columnaCodigo", "columnaDescripcion", "columnaCosto");
        }

        @Test
        @DisplayName("la primera fila de datos es 1 o mas")
        void filaDesdeMinimo() {
            assertThat(errores(new MapeoRequest(0, 0, 0, 1, 2))).containsOnlyKeys("filaDesde");
            assertThat(errores(new MapeoRequest(0, 1, 0, 1, 2))).isEmpty();
        }

        @Test
        @DisplayName("las columnas tienen un maximo de 255")
        void columnaMaxima() {
            assertThat(errores(new MapeoRequest(0, 2, 255, 1, 2))).isEmpty();
            assertThat(errores(new MapeoRequest(0, 2, 256, 1, 2))).containsOnlyKeys("columnaCodigo");
            assertThat(errores(new MapeoRequest(0, 2, 0, 256, 2))).containsOnlyKeys("columnaDescripcion");
            assertThat(errores(new MapeoRequest(0, 2, 0, 1, 256))).containsOnlyKeys("columnaCosto");
        }

        @Test
        @DisplayName("codigo y costo en la misma columna: columnas distintas")
        void codigoYCostoIguales() {
            Map<String, String> errores = errores(new MapeoRequest(0, 2, 2, 1, 2));

            assertThat(errores).containsEntry("columnasDistintas",
                    "Elegí columnas distintas para código, descripción y costo.");
        }

        @Test
        @DisplayName("descripcion igual a codigo o a costo tambien es invalida")
        void descripcionRepetida() {
            assertThat(errores(new MapeoRequest(0, 2, 0, 0, 2))).containsKey("columnasDistintas");
            assertThat(errores(new MapeoRequest(0, 2, 0, 2, 2))).containsKey("columnasDistintas");
        }

        @Test
        @DisplayName("con columnas faltantes no se suma el error de columnas distintas")
        void faltantesNoDuplicanElError() {
            assertThat(errores(new MapeoRequest(0, 2, null, null, null))).doesNotContainKey("columnasDistintas");
        }

        @Test
        @DisplayName("toMapeo arma el MapeoLista sin encabezados")
        void aMapeoLista() {
            assertThat(new MapeoRequest(1, 3, 4, 5, 6).toMapeo()).isEqualTo(new MapeoLista(1, 3, 4, 5, 6, null));
            assertThat(new MapeoRequest(0, 2, 0, null, 2).toMapeo().columnaDescripcion()).isNull();
        }
    }

    @Nested
    @DisplayName("ConteosRequest")
    class Conteos {

        @Test
        @DisplayName("seis contadores obligatorios y no negativos")
        void obligatoriosYNoNegativos() {
            assertThat(errores(conteos())).isEmpty();
            assertThat(errores(new ConteosRequest(null, null, null, null, null, null)))
                    .containsOnlyKeys("suben", "bajan", "nuevos", "sinCambio", "noEstan", "conError");
            assertThat(errores(new ConteosRequest(-1, 0, 0, 0, 0, 0))).containsOnlyKeys("suben");
        }

        @Test
        @DisplayName("toConteos arma los seis contadores en su orden")
        void aConteos() {
            assertThat(new ConteosRequest(1, 2, 3, 4, 5, 6).toConteos())
                    .isEqualTo(new ConteosImportacion(1, 2, 3, 4, 5, 6));
        }
    }

    @Nested
    @DisplayName("Bonificaciones")
    class BonificacionesDelPedido {

        @Test
        @DisplayName("vacias o ausentes son validas")
        void ausentes() {
            assertThat(errores(vistaPrevia(null, null, null))).isEmpty();
            assertThat(errores(aplicar(null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("0 y 100 son validos, tambien con 2 decimales")
        void limites() {
            assertThat(errores(vistaPrevia("0", "100", "12.34"))).isEmpty();
            assertThat(errores(aplicar("0.00", "100.00", "99.99"))).isEmpty();
        }

        @Test
        @DisplayName("fuera de rango: el mensaje nombra la bonificacion 1, 2 o 3")
        void fueraDeRango() {
            assertThat(errores(vistaPrevia("101", null, null)))
                    .containsOnly(Map.entry("bonificacion1", "La bonificación 1 debe estar entre 0 y 100."));
            assertThat(errores(vistaPrevia(null, "-5", null)))
                    .containsOnly(Map.entry("bonificacion2", "La bonificación 2 debe estar entre 0 y 100."));
            assertThat(errores(aplicar(null, null, "100.01")))
                    .containsOnly(Map.entry("bonificacion3", "La bonificación 3 debe estar entre 0 y 100."));
            assertThat(errores(aplicar("150", null, null)))
                    .containsOnly(Map.entry("bonificacion1", "La bonificación 1 debe estar entre 0 y 100."));
        }

        @Test
        @DisplayName("mas de 2 decimales: el mensaje nombra la bonificacion")
        void demasiadosDecimales() {
            assertThat(errores(vistaPrevia("10.555", null, null)))
                    .containsOnly(Map.entry("bonificacion1", "La bonificación 1 puede tener como máximo 2 decimales."));
            assertThat(errores(aplicar(null, "1.001", null)))
                    .containsOnly(Map.entry("bonificacion2", "La bonificación 2 puede tener como máximo 2 decimales."));
            assertThat(errores(aplicar(null, null, "0.123")))
                    .containsOnly(Map.entry("bonificacion3", "La bonificación 3 puede tener como máximo 2 decimales."));
        }

        @Test
        @DisplayName("el @Valid del controlador (grupo por defecto) no mira las bonificaciones: las valida el servicio")
        void elGrupoPorDefectoNoValidaLasBonificaciones() {
            assertThat(validador.validate(vistaPrevia("150", "-5", "10.555"))).isEmpty();
            assertThat(validador.validate(aplicar("150", "-5", "10.555"))).isEmpty();
            assertThat(validador.validate(vistaPrevia("150", "-5", "10.555"), ValidacionBonificaciones.class))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("bonificacion1", "bonificacion2", "bonificacion3");
        }

        @Test
        @DisplayName("toBonificaciones respeta los nulos")
        void aBonificaciones() {
            assertThat(vistaPrevia("40", null, "10").toBonificaciones())
                    .isEqualTo(new Bonificaciones(bd("40"), null, bd("10")));
            assertThat(aplicar(null, null, null).toBonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
        }
    }

    @Nested
    @DisplayName("VistaPreviaRequest y AplicarRequest")
    class Pedidos {

        @Test
        @DisplayName("proveedor y mapeo son obligatorios")
        void obligatorios() {
            assertThat(errores(new VistaPreviaRequest(null, null, false, null, null, null)))
                    .containsOnlyKeys("proveedorId", "mapeo");
            assertThat(errores(new AplicarRequest(null, null, false, null, null, null, false, null, null)))
                    .containsOnlyKeys("proveedorId", "mapeo", "conteosEsperados");
        }

        @Test
        @DisplayName("el mapeo anidado se valida con su ruta")
        void mapeoAnidado() {
            VistaPreviaRequest pedido = new VistaPreviaRequest(
                    7L, new MapeoRequest(0, 2, 2, null, 2), false, null, null, null);

            assertThat(errores(pedido)).containsKey("mapeo.columnasDistintas");
        }

        @Test
        @DisplayName("aplicar valida los conteos anidados")
        void conteosAnidados() {
            AplicarRequest pedido = new AplicarRequest(
                    7L, mapeo(), false, null, null, null, true, 3L, new ConteosRequest(-1, 0, 0, 0, 0, 0));

            assertThat(errores(pedido)).containsOnlyKeys("conteosEsperados.suben");
        }

        @Test
        @DisplayName("aplicar completo es valido y expone crearNuevos y rubroId")
        void aplicarValido() {
            AplicarRequest pedido = new AplicarRequest(
                    7L, mapeo(), true, bd("40"), bd("10"), null, true, 3L, conteos());

            assertThat(errores(pedido)).isEmpty();
            assertThat(pedido.crearNuevos()).isTrue();
            assertThat(pedido.rubroId()).isEqualTo(3L);
            assertThat(pedido.listaIncluyeIva()).isTrue();
            assertThat(pedido.toBonificaciones()).isEqualTo(new Bonificaciones(bd("40"), bd("10"), null));
        }
    }
}
