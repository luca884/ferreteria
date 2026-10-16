package com.ferreteria.catalogo.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Moneda;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

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

    /** Como lo arma Spring MVC: sin fallar por propiedades desconocidas. */
    private static final ObjectMapper JSON_DE_SPRING = Jackson2ObjectMapperBuilder.json().build();

    /** Mapeo del corte 2: hoja 0, datos desde la 2, codigo 0, descripcion 1, costo 2 y los campos nuevos. */
    private static MapeoRequest ampliado(List<Integer> hojas, Integer columnaMoneda, Moneda monedaFija,
                                         Integer sinIva, Integer conIva, Integer iva,
                                         Integer ajuste1, Integer ajuste2, Integer ajuste3,
                                         Integer barras, Integer unidad, Integer rubro) {
        return new MapeoRequest(0, 2, 0, 1, 2, hojas, columnaMoneda, monedaFija, sinIva, conIva, iva,
                ajuste1, ajuste2, ajuste3, barras, unidad, rubro);
    }

    @Nested
    @DisplayName("MapeoRequest del corte 2")
    class MapeoAmpliado {

        @Test
        @DisplayName("el cuerpo viejo, sin hojas ni campos nuevos, es valido y llega al mapeo con todo en null")
        void cuerpoViejo() throws Exception {
            MapeoRequest pedido = JSON_DE_SPRING.readValue(
                    "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,\"columnaCosto\":2}",
                    MapeoRequest.class);

            assertThat(errores(pedido)).isEmpty();
            assertThat(pedido.toMapeo()).isEqualTo(new MapeoLista(0, 2, 0, 1, 2, null));
        }

        @Test
        @DisplayName("un mapeo completo con todos los campos nuevos es valido y se copia entero al MapeoLista")
        void completo() {
            MapeoRequest pedido = ampliado(List.of(0, 2), 4, null, 7, 8, 9, 10, 11, 12, 13, 14, 15);

            assertThat(errores(pedido)).isEmpty();
            assertThat(pedido.toMapeo()).isEqualTo(new MapeoLista(0, 2, 0, 1, 2, null, List.of(0, 2), 4, null, 7, 8, 9,
                    10, 11, 12, 13, 14, 15));
        }

        @Test
        @DisplayName("la moneda fija viaja al mapeo")
        void monedaFija() {
            MapeoRequest pedido = ampliado(null, null, Moneda.USD, null, null, null, null, null, null, null, null, null);

            assertThat(errores(pedido)).isEmpty();
            assertThat(pedido.toMapeo().monedaFija()).isEqualTo(Moneda.USD);
        }

        @Test
        @DisplayName("columna de moneda y moneda fija juntas: error en español")
        void monedaColumnaYFija() {
            MapeoRequest pedido = ampliado(null, 4, Moneda.USD, null, null, null, null, null, null, null, null, null);

            assertThat(errores(pedido)).containsEntry("monedaUnica",
                    "Elegí una columna de moneda o una moneda fija, no las dos.");
        }

        @Test
        @DisplayName("moneda fija EUR no es una moneda: el cuerpo no se puede leer")
        void monedaFijaEuro() {
            assertThatThrownBy(() -> JSON_DE_SPRING.readValue(
                    "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaCosto\":2,\"monedaFija\":\"EUR\"}",
                    MapeoRequest.class))
                    .isInstanceOf(InvalidFormatException.class)
                    .satisfies(e -> assertThat(((InvalidFormatException) e).getTargetType()).isEqualTo(Moneda.class));
        }

        @Test
        @DisplayName("el costo sin IVA sin el con IVA, o al reves, es un error")
        void parDeIvaIncompleto() {
            String mensaje = "Para calcular el IVA elegí las dos columnas: el precio sin IVA y el precio con IVA.";

            assertThat(errores(ampliado(null, null, null, 7, null, null, null, null, null, null, null, null)))
                    .containsEntry("parDeIvaCompleto", mensaje);
            assertThat(errores(ampliado(null, null, null, null, 8, null, null, null, null, null, null, null)))
                    .containsEntry("parDeIvaCompleto", mensaje);
            assertThat(errores(ampliado(null, null, null, 7, 8, null, null, null, null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("el costo sin IVA puede ser la misma columna que el costo")
        void sinIvaIgualAlCosto() {
            assertThat(errores(ampliado(null, null, null, 2, 8, null, null, null, null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("el costo sin IVA no puede repetir otra columna que no sea el costo")
        void sinIvaRepetido() {
            assertThat(errores(ampliado(null, null, null, 0, 8, null, null, null, null, null, null, null)))
                    .containsKey("camposDistintos");
            assertThat(errores(ampliado(null, null, null, 8, 8, null, null, null, null, null, null, null)))
                    .containsKey("camposDistintos");
        }

        @Test
        @DisplayName("columnas repetidas entre los campos nuevos o con los viejos: error")
        void columnasRepetidas() {
            Map<String, String> conMoneda = errores(
                    ampliado(null, 1, null, null, null, null, null, null, null, null, null, null));
            Map<String, String> ajustes = errores(
                    ampliado(null, null, null, null, null, null, 10, 10, null, null, null, null));
            Map<String, String> rubroConCosto = errores(
                    ampliado(null, null, null, null, null, null, null, null, null, null, null, 2));

            assertThat(conMoneda).containsEntry("camposDistintos", "Elegí columnas distintas para cada campo de la lista.");
            assertThat(ajustes).containsKey("camposDistintos");
            assertThat(rubroConCosto).containsKey("camposDistintos");
        }

        @Test
        @DisplayName("si se repiten solo codigo, descripcion y costo, el error es el de siempre y no se duplica")
        void soloLosViejos() {
            Map<String, String> errores = errores(new MapeoRequest(0, 2, 2, 1, 2));

            assertThat(errores).containsOnlyKeys("columnasDistintas");
        }

        @Test
        @DisplayName("hojas con indices negativos o repetidos: error")
        void hojasInvalidas() {
            String mensaje = "Las hojas elegidas no son válidas: no pueden repetirse ni ser negativas.";

            assertThat(errores(ampliado(List.of(0, -1), null, null, null, null, null, null, null, null, null, null, null)))
                    .containsEntry("hojasValidas", mensaje);
            assertThat(errores(ampliado(List.of(1, 1), null, null, null, null, null, null, null, null, null, null, null)))
                    .containsEntry("hojasValidas", mensaje);
            assertThat(errores(ampliado(List.of(0, 1, 69), null, null, null, null, null, null, null, null, null, null, null)))
                    .isEmpty();
        }

        @Test
        @DisplayName("los indices de las columnas nuevas no pueden ser negativos ni pasar de 255")
        void indicesDeLasColumnasNuevas() {
            Map<String, String> errores = errores(ampliado(null, -1, null, -1, 256, -2, -3, 256, -4, -5, -6, -7));

            assertThat(errores).containsKeys("columnaMoneda", "columnaSinIva", "columnaConIva", "columnaIva",
                    "columnaAjuste1", "columnaAjuste2", "columnaAjuste3", "columnaCodigoBarras", "columnaUnidad",
                    "columnaRubro");
            assertThat(errores.get("columnaIva")).isEqualTo("La columna del % de IVA no es válida.");
        }

        @Test
        @DisplayName("un cuerpo con columnaStock o columnaMarca se ignora y no llega al mapeo")
        void stockYMarcaSeIgnoran() throws Exception {
            MapeoRequest pedido = JSON_DE_SPRING.readValue(
                    "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,\"columnaCosto\":2,"
                            + "\"columnaStock\":3,\"columnaMarca\":4}", MapeoRequest.class);

            JsonNode mapeo = JSON_DE_SPRING.valueToTree(pedido.toMapeo());

            assertThat(errores(pedido)).isEmpty();
            assertThat(mapeo.has("columnaStock")).isFalse();
            assertThat(mapeo.has("columnaMarca")).isFalse();
            assertThat(pedido.toMapeo()).isEqualTo(new MapeoLista(0, 2, 0, 1, 2, null));
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

        @Test
        @DisplayName("la vista previa acepta un rubroId opcional: el pedido de antes (sin rubro) queda con null")
        void vistaPreviaConRubroOpcional() {
            VistaPreviaRequest conRubro = new VistaPreviaRequest(7L, mapeo(), false, null, null, null, 3L);
            VistaPreviaRequest deAntes = new VistaPreviaRequest(7L, mapeo(), false, null, null, null);

            assertThat(errores(conRubro)).isEmpty();
            assertThat(conRubro.rubroId()).isEqualTo(3L);
            assertThat(errores(deAntes)).isEmpty();
            assertThat(deAntes.rubroId()).isNull();
        }

        @Test
        @DisplayName("el JSON de la vista previa sin rubroId (cliente viejo) se lee con rubroId null; con rubroId, con su valor")
        void vistaPreviaDesdeJson() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String base = "{\"proveedorId\":7,\"listaIncluyeIva\":false,\"mapeo\":{\"hoja\":0,\"filaDesde\":2,"
                    + "\"columnaCodigo\":0,\"columnaDescripcion\":1,\"columnaCosto\":2}";

            VistaPreviaRequest viejo = mapper.readValue(base + "}", VistaPreviaRequest.class);
            VistaPreviaRequest nuevo = mapper.readValue(base + ",\"rubroId\":5}", VistaPreviaRequest.class);

            assertThat(viejo.rubroId()).isNull();
            assertThat(viejo.proveedorId()).isEqualTo(7L);
            assertThat(nuevo.rubroId()).isEqualTo(5L);
        }
    }
}
