package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.repository.ArticuloRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * El EXPLAIN del filtro de articulos sobre el SQL real que genero Hibernate (lote 5b, C-226). Los valores se ligan en el
 * orden en que aparecen los {@code :nombre} de {@link ArticuloRepository#FILTRO_ARTICULOS}, leidos del mismo texto con una
 * regex: si el filtro cambia, la prueba sigue el cambio sin tocarla. Como cada aparicion es un {@code ?} del SQL, se
 * verifica que las cantidades coincidan.
 */
public final class ExplainDelFiltro {

    private static final Pattern PARAMETRO = Pattern.compile(":([A-Za-z]+)");

    private ExplainDelFiltro() {
    }

    /**
     * @param sqlCapturado lo que devolvio {@link SqlCapturado#durante} de una consulta del filtro
     * @param valores      el valor de cada parametro por nombre ({@code fid}, {@code proveedorId}, {@code rubroId},
     *                     {@code importacionId}, {@code q}, {@code incluirInactivos}); un null es "no filtra"
     */
    public static List<Map<String, Object>> explain(JdbcTemplate jdbc, List<String> sqlCapturado,
                                                    Map<String, Object> valores) {
        String sql = sqlCapturado.stream()
                .filter(s -> s.startsWith("select") && s.contains("from articulo"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se capturo el select de articulo: " + sqlCapturado));
        List<Object> argumentos = new ArrayList<>();
        Matcher aparicion = PARAMETRO.matcher(ArticuloRepository.FILTRO_ARTICULOS);
        while (aparicion.find()) {
            String nombre = aparicion.group(1);
            assertThat(valores).as("valor del parametro :" + nombre).containsKey(nombre);
            argumentos.add(valores.get(nombre));
        }
        assertThat(sql.chars().filter(c -> c == '?').count())
                .as("los ? del SQL real contra los :nombre de FILTRO_ARTICULOS\n" + sql)
                .isEqualTo(argumentos.size());
        return jdbc.queryForList("explain " + sql, argumentos.toArray());
    }
}
