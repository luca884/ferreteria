package com.ferreteria.facturacion.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.credencial.CredencialArcaRepository;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * La credencial del simulador de las demo en el perfil dev (rama 6, S6): el arranque la deja activa en las que facturan con el
 * simulador y no hace nada si ya la tienen; nunca va como dato en {@code db/dev-data} ni {@code db/dev-only}. Corre con
 * ferreterías propias en lugar de las demo, que comparte toda la suite.
 */
class CredencialesDemoDelSimuladorIntegrationTest extends IntegrationTestBase {

    @Autowired
    FerreteriaRepository ferreterias;
    @Autowired
    ConfigArcaRepository configs;
    @Autowired
    CredencialArcaRepository credencialesGuardadas;
    @Autowired
    CredencialArcaService credenciales;
    @Autowired
    PlatformTransactionManager transacciones;
    @Autowired
    ApplicationContext contexto;

    @Test
    @DisplayName("deja una credencial activa del simulador en la demo con SIMULADOR, una sola vez; a la de homologación no la "
            + "toca")
    void dejaLaCredencialUnaSolaVez() {
        String enSimulador = ferreteria("SIMULADOR");
        String enHomologacion = ferreteria("HOMOLOGACION");
        CredencialesDemoDelSimulador arranque = new CredencialesDemoDelSimulador(ferreterias, configs, credencialesGuardadas,
                credenciales, transacciones, Set.of(enSimulador, enHomologacion));

        arranque.run(new DefaultApplicationArguments());
        List<Map<String, Object>> primera = credencialesDe(enSimulador);
        arranque.run(new DefaultApplicationArguments());

        assertThat(primera).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("estado", "ACTIVA").containsEntry("ambiente", "SIMULADOR");
            assertThat(c.get("ca_simulador_id")).isNotNull();
        });
        assertThat(credencialesDe(enSimulador)).isEqualTo(primera);
        assertThat(credencialesDe(enHomologacion)).isEmpty();
    }

    @Test
    @DisplayName("fuera del perfil dev no hay arranque, y db/dev-data y db/dev-only no traen credenciales, CA ni tickets")
    void soloEnDevYSinDatosCifrados() throws IOException {
        assertThat(contexto.getBeansOfType(CredencialesDemoDelSimulador.class)).isEmpty();

        Pattern cifrado = Pattern.compile("credencial_arca|ca_simulador|ticket_acceso|ticket_simulado|clave_privada",
                Pattern.CASE_INSENSITIVE);
        for (String ubicacion : List.of("src/main/resources/db/dev-data", "src/main/resources/db/dev-only")) {
            try (Stream<Path> archivos = Files.walk(Path.of(ubicacion))) {
                List<Path> sql = archivos.filter(a -> a.toString().endsWith(".sql")).toList();
                assertThat(sql).as(ubicacion).isNotEmpty();
                assertThat(sql.stream().filter(a -> cifrado.matcher(leer(a)).find()).toList()).as(ubicacion).isEmpty();
            }
        }
    }

    /** Una ferretería RI con los datos de CU-01 y {@code config_arca} en ese ambiente (punto 1), sin credencial: su CUIT. */
    private String ferreteria(String ambiente) {
        String cuit = MotorFiscalTestBase.conGuiones(MotorFiscalTestBase.cuitNuevo());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", "Demo " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta) values (?, true, ?, 1)", id,
                ambiente);
        return cuit;
    }

    private List<Map<String, Object>> credencialesDe(String cuit) {
        return jdbc.queryForList("select c.id, c.estado, c.ambiente, c.huella, c.ca_simulador_id from credencial_arca c join "
                + "ferreteria f on f.id = c.ferreteria_id where f.cuit = ? order by c.id", cuit);
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
