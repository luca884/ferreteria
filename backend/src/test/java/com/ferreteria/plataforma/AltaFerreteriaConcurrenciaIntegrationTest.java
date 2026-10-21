package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.ferreteria.auth.service.EmailUnico;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * R-103 y R-105: dos altas a la vez que pasan el pre-chequeo no terminan en 500 ni dejan una ferretería a medias. El choque
 * lo resuelve la unique de la base (CUIT o email).
 */
class AltaFerreteriaConcurrenciaIntegrationTest extends PlataformaTestBase {

    @SpyBean
    FerreteriaRepository ferreterias;

    @SpyBean
    EmailUnico emailUnico;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate sql;

    private List<Integer> correrJuntos(List<Callable<Integer>> trabajos) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> t : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return t.call();
                }));
            }
            largada.countDown();
            List<Integer> codigos = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                codigos.add(f.get(60, TimeUnit.SECONDS));
            }
            return codigos;
        } finally {
            pool.shutdownNow();
        }
    }

    private int codigoDeAlta(String token, String cuerpo) throws Exception {
        return postJson(BASE, token, cuerpo).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("dos altas con el mismo CUIT que pasan el pre-chequeo: una da 201 y la otra 409, nunca 500")
    void mismoCuit() throws Exception {
        String token = tokenSuperAdmin();
        String cuit = cuitNuevo();
        doReturn(false).when(ferreterias).existsByCuit(anyString());
        String a = altaValida(cuit, unico("a") + "@concurrencia.test").toString();
        String b = altaValida(cuit, unico("b") + "@concurrencia.test").toString();

        List<Integer> codigos = correrJuntos(List.of(() -> codigoDeAlta(token, a), () -> codigoDeAlta(token, b)));

        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from ferreteria where cuit = ?", Long.class, cuit)).isEqualTo(1L);
    }

    @Test
    @DisplayName("dos altas con CUITs distintos y el mismo email: una da 201, la otra 409 y queda una sola ferretería")
    void mismoEmail() throws Exception {
        String token = tokenSuperAdmin();
        String cuit1 = cuitNuevo();
        String cuit2 = cuitNuevo();
        String email = unico("repetido") + "@concurrencia.test";
        doReturn(false).when(emailUnico).enUso(anyString());
        String a = altaValida(cuit1, email).toString();
        String b = altaValida(cuit2, email).toString();

        List<Integer> codigos = correrJuntos(List.of(() -> codigoDeAlta(token, a), () -> codigoDeAlta(token, b)));

        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from ferreteria where cuit in (?, ?)", Long.class, cuit1, cuit2))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Long.class, email)).isEqualTo(1L);
    }
}
