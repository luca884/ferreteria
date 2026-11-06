package com.ferreteria.catalogo.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Los textos de encabezado que se reconocen como cada campo mapeable. Se comparan por IGUALDAD, ya normalizados
 * (sin mayusculas, tildes, puntuacion ni espacios de mas), y el orden de cada lista es su prioridad: si dos
 * columnas son costo, gana la del sinonimo mas arriba. Asi "Lista (unidad)" es costo y no la unidad de medida.
 *
 * <p>Las columnas que se ignoran adrede no figuran en ninguna lista: Stock, Bulto, Sinonimo, Marca, Cantidad x
 * Caja, percepciones. Nunca se proponen.
 */
public final class SinonimosDeColumnas {

    /** Los campos que se pueden mapear (la lista cerrada de la decision 13). */
    public enum Campo {
        CODIGO, DESCRIPCION, COSTO, MONEDA, SIN_IVA, CON_IVA, IVA, AJUSTE1, AJUSTE2, AJUSTE3, CODIGO_BARRAS, UNIDAD,
        RUBRO
    }

    /** El campo que reconoce un texto y que tan arriba esta su sinonimo (0 es el mas prioritario). */
    public record Coincidencia(Campo campo, int prioridad) {}

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Map<Campo, List<String>> SINONIMOS = new EnumMap<>(Campo.class);

    static {
        // El del proveedor va antes que «Código» solo: si el archivo trae los dos, «Código» suele ser el interno de
        // otro sistema (QA 2026-11-05, B y C).
        SINONIMOS.put(Campo.CODIGO, List.of("CODIGO PROVEEDOR", "CODIGO DEL PROVEEDOR", "COD PROVEEDOR", "CODIGO",
            "COD", "CODIGO ARTICULO", "COD ARTICULO", "SKU", "REFERENCIA"));
        SINONIMOS.put(Campo.DESCRIPCION, List.of("DESCRIPCION", "ARTICULO", "DETALLE", "PRODUCTO", "NOMBRE",
            "DENOMINACION"));
        SINONIMOS.put(Campo.COSTO, List.of("PRECIO X UNIDAD", "LISTA UNIDAD", "PRECIO UNITARIO", "LISTA", "PRECIO",
            "COSTO", "PRECIO LISTA", "PRECIO DE LISTA"));
        SINONIMOS.put(Campo.MONEDA, List.of("MON", "MONEDA", "DIVISA"));
        // «s/IVA» normaliza a «S IVA» (la barra es un separador).
        SINONIMOS.put(Campo.SIN_IVA, List.of("SIN IVA", "PRECIO SIN IVA", "NETO", "PRECIO NETO", "S IVA",
            "PRECIO S IVA", "P S IVA", "PRECIO UNITARIO S IVA", "UNITARIO S IVA"));
        SINONIMOS.put(Campo.CON_IVA, List.of("CON IVA", "PRECIO CON IVA"));
        SINONIMOS.put(Campo.IVA, List.of("IVA", "ALICUOTA", "ALICUOTA IVA", "PORCENTAJE IVA"));
        SINONIMOS.put(Campo.AJUSTE1, List.of("DESCUENTO 1", "DESC 1", "DTO 1", "RECARGO 1", "AJUSTE 1"));
        SINONIMOS.put(Campo.AJUSTE2, List.of("DESCUENTO 2", "DESC 2", "DTO 2", "RECARGO 2", "AJUSTE 2"));
        SINONIMOS.put(Campo.AJUSTE3, List.of("DESCUENTO 3", "DESC 3", "DTO 3", "RECARGO 3", "AJUSTE 3"));
        SINONIMOS.put(Campo.CODIGO_BARRAS, List.of("CODIGO DE BARRAS", "CODIGO BARRAS", "COD BARRAS", "EAN", "EAN13",
            "BARRAS"));
        SINONIMOS.put(Campo.UNIDAD, List.of("UNIDAD", "UNIDAD DE MEDIDA", "UM"));
        SINONIMOS.put(Campo.RUBRO, List.of("RUBRO", "FAMILIA", "CATEGORIA"));
    }

    private SinonimosDeColumnas() {
    }

    /** Cuantos sinonimos tiene un campo: sirve para ponderar la prioridad. */
    static int cantidad(Campo campo) {
        return SINONIMOS.get(campo).size();
    }

    /** "Lista (unidad)" es LISTA UNIDAD; "% IVA" es IVA; un texto vacio es "". */
    public static String normalizar(String texto) {
        String clave = NormalizadorCodigo.clave(texto);
        return clave == null ? "" : NO_ALFANUMERICO.matcher(clave).replaceAll(" ").strip();
    }

    /** El campo cuyo sinonimo es igual al texto ya normalizado, o vacio si no es ninguno. */
    public static Optional<Coincidencia> buscar(String textoNormalizado) {
        for (Map.Entry<Campo, List<String>> entrada : SINONIMOS.entrySet()) {
            int prioridad = entrada.getValue().indexOf(textoNormalizado);
            if (prioridad >= 0) {
                return Optional.of(new Coincidencia(entrada.getKey(), prioridad));
            }
        }
        return Optional.empty();
    }
}
