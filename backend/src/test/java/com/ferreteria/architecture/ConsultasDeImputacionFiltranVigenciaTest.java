package com.ferreteria.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.repository.ImputacionRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * CCT3B1-202 (CC-063, CC-102): toda consulta ({@code @Query}) de {@link ImputacionRepository} filtra por vigencia, salvo una
 * lista EXPLÍCITA de las que necesitan ver también las imputaciones de un recibo anulado. Una consulta nueva sin
 * {@code vigente} rompe el build: así nadie vuelve a restar del pendiente una imputación anulada sin querer. Por reflexión,
 * sin Spring.
 */
class ConsultasDeImputacionFiltranVigenciaTest {

    /**
     * Las que NO filtran, a propósito: la reimpresión y la cobranza repetida (ven las originales), y la toma por id de la
     * anulación (que justamente informa la vigencia, ver {@code bloquearDeRecibo}).
     */
    static final Set<String> SIN_FILTRO_A_PROPOSITO = Set.of("deRecibo", "idsDeRecibo", "bloquearDeRecibo");

    private static List<Method> consultas() {
        return Arrays.stream(ImputacionRepository.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Query.class)).toList();
    }

    /**
     * ¿El {@code where} de la consulta menciona la vigencia? Solo cuenta el {@code where}: que {@code bloquearDeRecibo} la
     * traiga en el {@code select} (para informarla) no es filtrar.
     */
    private static boolean mencionaVigente(Method consulta) {
        String consultaEnMinusculas = consulta.getAnnotation(Query.class).value().toLowerCase(java.util.Locale.ROOT);
        int donde = consultaEnMinusculas.indexOf(" where ");
        return donde >= 0 && consultaEnMinusculas.substring(donde).contains("vigente");
    }

    @Test
    @DisplayName("toda @Query de ImputacionRepository contiene «vigente», salvo la lista explícita de las que ven también las no vigentes")
    void todaConsultaFiltraVigencia() {
        List<Method> consultas = consultas();
        assertThat(consultas).as("el repositorio tiene consultas").hasSizeGreaterThanOrEqualTo(7);

        List<String> sinFiltro = consultas.stream().filter(m -> !mencionaVigente(m)).map(Method::getName).sorted().toList();

        assertThat(sinFiltro).as("consultas de ImputacionRepository que no mencionan «vigente» (agregá el filtro o, si debe ver "
                + "también las no vigentes, sumala a SIN_FILTRO_A_PROPOSITO con su porqué)")
                .containsExactlyInAnyOrderElementsOf(SIN_FILTRO_A_PROPOSITO);
    }

    @Test
    @DisplayName("la whitelist no se pudre: cada método listado existe y es una consulta; y la baja por anulación sí menciona «vigente»")
    void laListaNoSePudre() {
        Set<String> nombres = new java.util.HashSet<>();
        consultas().forEach(m -> nombres.add(m.getName()));

        assertThat(nombres).containsAll(SIN_FILTRO_A_PROPOSITO);
        assertThat(consultas()).filteredOn(m -> m.getName().equals("desactivarDeRecibo")).singleElement()
                .satisfies(m -> assertThat(mencionaVigente(m)).isTrue());
        assertThat(consultas()).filteredOn(m -> Set.of("imputadoPorComprobante", "imputadoBloqueado", "totalImputado")
                .contains(m.getName())).hasSize(3).allMatch(ConsultasDeImputacionFiltranVigenciaTest::mencionaVigente);
    }
}
