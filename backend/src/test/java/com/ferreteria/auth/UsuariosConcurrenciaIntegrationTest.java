package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ferreteria.auth.service.EmailUnico;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.BarreraDeLocks.Largada;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;

/**
 * R-010 y R-008: la ferretería nunca se queda sin Dueño activo, y dos altas con el mismo email no terminan en 500.
 * Sincronización sin tiempos: se espera la condición observable «dos transacciones en LOCK WAIT» de MySQL.
 */
class UsuariosConcurrenciaIntegrationTest extends UsuariosTestBase {

    @SpyBean
    EmailUnico emailUnico;

    private HolderJdbc holder;

    @AfterEach
    void soltar() {
        if (holder != null) {
            holder.rollback();
        }
    }

    private int codigo(String url, String token, String metodo, String cuerpo) throws Exception {
        var pedido = "PATCH".equals(metodo)
                ? patch(url)
                : post(url);
        return mvc.perform(pedido.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo)).andReturn().getResponse().getStatus();
    }

    private List<Integer> correrJuntos(List<Callable<Integer>> trabajos, Largada largada, Runnable cuandoEstenListos)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> t : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return t.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            cuandoEstenListos.run();
            List<Integer> codigos = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                codigos.add(f.get(60, TimeUnit.SECONDS));
            }
            return codigos;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("dos Dueños que se desactivan a la vez: uno da 200, el otro 422, y queda un Dueño activo, sin deadlocks")
    void dosDuenosSeDesactivanMutuamente() throws Exception {
        long ferreteria = crearFerreteria("Concurrencia");
        String email1 = unico("d1") + "@concurrencia.test";
        String email2 = unico("d2") + "@concurrencia.test";
        long d1 = crearUsuario(ferreteria, email1, "Dueño Uno", "DUENO");
        long d2 = crearUsuario(ferreteria, email2, "Dueño Dos", "DUENO");
        String token1 = login(email1);
        String token2 = login(email2);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        holder = BarreraDeLocks.holderJdbc("select id from ferreteria where id = " + ferreteria + " for update");
        List<Integer> codigos = correrJuntos(List.of(
                () -> codigo("/api/usuarios/" + d2 + "/activo", token1, "PATCH", "{\"activo\":false}"),
                () -> codigo("/api/usuarios/" + d1 + "/activo", token2, "PATCH", "{\"activo\":false}")),
                new Largada(2), () -> {
                    barrera.esperarBloqueados(2);
                    holder.commit();
                });

        assertThat(codigos).containsExactlyInAnyOrder(200, 422);
        assertThat(jdbc.queryForObject(
                "select count(*) from usuario where ferreteria_id = ? and rol = 'DUENO' and activo = true", Long.class,
                ferreteria)).isEqualTo(1L);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("dos altas con el mismo email que pasan el pre-chequeo: una da 201 y la otra 409, nunca 500")
    void dosAltasConElMismoEmail() throws Exception {
        long ferreteria = crearFerreteria("Concurrencia email");
        String emailDueno = unico("d") + "@concurrencia.test";
        crearUsuario(ferreteria, emailDueno, "Dueño", "DUENO");
        String token = login(emailDueno);
        String repetido = unico("repetido") + "@concurrencia.test";
        // El pre-chequeo deja pasar a los dos: el choque lo resuelve la unique de la base.
        doReturn(false).when(emailUnico).enUso(anyString());
        String cuerpo = json("nombre", "Repetido", "email", repetido, "rol", "VENDEDOR", "password", "demo1234");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Integer> codigos = correrJuntos(List.of(
                () -> codigo("/api/usuarios", token, "POST", cuerpo),
                () -> codigo("/api/usuarios", token, "POST", cuerpo)),
                new Largada(2), () -> { });

        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Long.class, repetido)).isEqualTo(1L);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
