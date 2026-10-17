package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.support.GanchoDeChoque;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/**
 * El choque de version en la cascada del margen del rubro y en la baja del articulo (R-228: la cascada del rubro NO
 * reintenta, un choque da el 409 de version al guardar el rubro; la baja tambien termina en ese 409). CUIT
 * 30-78401071-1. La cascada se choca con el mismo {@link GanchoDeChoque} que el recalculo (historial). La baja no
 * escribe historial, asi que su gancho espia {@code findScoped} del repositorio de articulos: despues de leer el
 * articulo y antes de confirmar, otro hilo le sube la version por JDBC. El DELETE no lleva version en el cuerpo, de
 * modo que el unico camino a un 409 es ese choque al confirmar.
 */
class RubroYBajaVersionIntegrationTest extends VersionTestBase {

    @SpyBean
    ArticuloRepository articuloRepository;

    @Autowired
    EntityManager entityManager;

    private Long f;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78401071-1", "Version rubro y baja");
        borrarDatosDe(f);
        dueno = login(usuario(f, "dueno-rb@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        reset(articuloRepository);
        borrarDatosDe(f);
    }

    private void tocarPorJdbc(Long id) {
        jdbc.update("update articulo set descripcion = concat(descripcion, 'x'), version = version + 1 where id = ?", id);
    }

    private BigDecimal margenDelRubro(Long rubro) {
        return jdbc.queryForObject("select margen_porcentaje from rubro where id = ?", BigDecimal.class, rubro);
    }

    @Test
    @DisplayName("cambiar el margen del rubro con un articulo heredero que choca: 409 con el mensaje de version y nada escrito")
    void laCascadaDelRubroQueChocaDa409YNoEscribeNada() throws Exception {
        Long rubro = rubro(f, "Rubro del choque", "30.00");
        Long a = manual(f, "RB-A", "100.00", "160.00");
        Long b = manual(f, "RB-B", "100.00", "160.00");
        jdbc.update("update articulo set rubro_id = ? where id in (?, ?)", rubro, a, b);
        long versionB = versionDe(b);
        ganchoEnSaveAll(() -> tocarPorJdbc(a));

        putJson("/api/rubros/" + rubro, dueno, "{\"nombre\":\"Rubro del choque\",\"margenPorcentaje\":50}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)))
                .andExpect(jsonPath("$.errors", nullValue()));

        // El rubro, los precios y el historial quedaron como estaban; solo cambio lo que hizo el otro hilo.
        assertThat(margenDelRubro(rubro)).isEqualByComparingTo("30.00");
        assertThat(precioDe(a)).isEqualByComparingTo("160");
        assertThat(precioDe(b)).isEqualByComparingTo("160");
        assertThat(versionDe(b)).isEqualTo(versionB);
        assertThat(historialDeLaFerreteria(f)).isZero();
        assertThat(versionDe(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin choque la misma edicion del rubro anda: 200, margen 50 y precio nuevo (el 409 de arriba es por el choque)")
    void sinChoqueLaCascadaAnda() throws Exception {
        Long rubro = rubro(f, "Rubro sin choque", "30.00");
        Long a = manual(f, "RB-C", "100.00", "160.00");
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubro, a);

        putJson("/api/rubros/" + rubro, dueno, "{\"nombre\":\"Rubro sin choque\",\"margenPorcentaje\":50}")
                .andExpect(status().isOk());

        assertThat(margenDelRubro(rubro)).isEqualByComparingTo("50.00");
        assertThat(precioDe(a)).isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("dar de baja un articulo que otra escritura modifica antes de confirmar: 409 con el mensaje de version y el articulo sigue activo")
    void laBajaQueChocaDa409() throws Exception {
        Long id = manual(f, "RB-BAJA", "100.00", "160.00");
        gancharElFindDelArticulo(id);

        delete("/api/articulos/" + id, dueno).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_VERSION)))
                .andExpect(jsonPath("$.errors", nullValue()));

        assertThat(jdbc.queryForObject("select activo from articulo where id = ?", Boolean.class, id)).isTrue();
        assertThat(versionDe(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, id))
                .endsWith("x");
    }

    @Test
    @DisplayName("sin choque la baja anda aunque el articulo se haya editado antes: 204 y version +1 (el DELETE no lleva version)")
    void laBajaSinChoqueAnda() throws Exception {
        Long id = manual(f, "RB-BAJA2", "100.00", "160.00");
        tocarPorJdbc(id);

        delete("/api/articulos/" + id, dueno).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select activo from articulo where id = ?", Boolean.class, id)).isFalse();
        assertThat(versionDe(id)).isEqualTo(2);
    }

    /** Despues de leer el articulo en la baja, otro hilo le sube la version; sigue con el resultado ya leido. */
    private void gancharElFindDelArticulo(Long id) {
        AtomicBoolean yaCorrio = new AtomicBoolean();
        ArticuloRepository real = new JpaRepositoryFactory(entityManager).getRepository(ArticuloRepository.class);
        doAnswer(invocacion -> {
            Object leido;
            try {
                leido = invocacion.getMethod().invoke(real, invocacion.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (yaCorrio.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> tocarPorJdbc(id)).get(10, TimeUnit.SECONDS);
            }
            return leido;
        }).when(articuloRepository).findScoped(any());
    }
}
