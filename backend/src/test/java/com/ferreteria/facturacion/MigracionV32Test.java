package com.ferreteria.facturacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.BarreraDeLocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V32 (rama 2 de facturacion, CU-07 pasos 3 y 5): las tablas del modulo facturacion (config_arca, serie_fiscal,
 * comprobante_fiscal, comprobante_fiscal_alicuota e intento_fiscal). Aditiva: ninguna tabla existente cambia. Usa un MySQL de
 * Testcontainers propio de la clase y una base nueva por test: migra hasta la V31, siembra por JDBC y recien despues migra a
 * la V32. CUIT: bloque 30-7860132x, con digito verificador valido.
 */
class MigracionV32Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final AtomicInteger COMPROBANTES = new AtomicInteger();
    private static final List<String> TABLAS_NUEVAS = List.of("config_arca", "serie_fiscal", "comprobante_fiscal",
        "comprobante_fiscal_alicuota", "intento_fiscal");
    private static final String CUIT_A = "30-78601320-4";
    private static final String CUIT_B = "30-78601321-2";

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion31() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v32", "31"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa32() {
        flyway("classpath:db/migration").target("32").load().migrate();
    }

    @Test
    @DisplayName("crea las cinco tablas con sus columnas: ferreteria_id NOT NULL, plata en DECIMAL(15,2), horas en DATETIME(6)")
    void tablasYColumnas() {
        migrarALa32();

        assertThat(columnas("config_arca")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "de_prueba tinyint(1) NO", "ambiente varchar(20) YES",
            "punto_venta int YES", "facturacion_habilitada tinyint(1) NO", "habilitada_en datetime(6) YES"));
        assertThat(columnas("serie_fiscal")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "ambiente varchar(20) NO", "punto_venta int NO", "cbte_tipo int NO",
            "ultimo_autorizado bigint YES", "ultima_cbte_fch date YES", "lease_dueno varchar(36) YES",
            "lease_vence datetime(6) YES"));
        assertThat(columnas("comprobante_fiscal")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "comprobante_id bigint NO", "ambiente varchar(20) NO",
            "clase varchar(20) NO", "letra varchar(1) NO", "cbte_tipo int NO", "punto_venta int NO", "numero bigint YES",
            "cbte_fch date YES", "estado varchar(30) NO", "cae varchar(14) YES", "cae_vencimiento date YES",
            "resultado varchar(1) YES", "observaciones json YES", "errores json YES", "receptor_doc_tipo int NO",
            "receptor_doc_nro bigint NO", "receptor_condicion_iva_id int NO", "receptor_nombre varchar(120) YES",
            "receptor_domicilio varchar(200) YES", "emisor_cuit varchar(13) NO", "emisor_razon_social varchar(150) NO",
            "emisor_condicion_iva varchar(30) NO", "emisor_domicilio_comercial varchar(200) NO",
            "emisor_ingresos_brutos varchar(50) NO", "emisor_inicio_actividades date NO", "imp_total decimal(15,2) NO",
            "imp_neto decimal(15,2) NO", "imp_iva decimal(15,2) NO", "imp_tot_conc decimal(15,2) NO",
            "imp_op_ex decimal(15,2) NO", "registrado_en datetime(6) NO"));
        assertThat(columnas("comprobante_fiscal_alicuota")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "comprobante_fiscal_id bigint NO", "iva_id int NO",
            "base_imp decimal(15,2) NO", "importe decimal(15,2) NO"));
        assertThat(columnas("intento_fiscal")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "comprobante_fiscal_id bigint NO", "serie_fiscal_id bigint NO",
            "numero bigint NO", "cbte_fch date NO", "estado varchar(20) NO", "resultado varchar(1) YES",
            "pedido mediumtext NO", "respuesta mediumtext YES", "respuesta_verificacion mediumtext YES",
            "iniciado_en datetime(6) NO", "terminado_en datetime(6) YES", "cerrado_en datetime(6) YES"));
    }

    @Test
    @DisplayName("las FK compuestas no dejan cruzar ferreterias: nada de A apunta a un comprobante, registro o serie de B")
    void lasFkCompuestasNoCruzanFerreterias() {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);
        Escenario b = escenarioCompleto(CUIT_B);
        long otroComprobanteDeB = comprobante(b.ferreteria(), b.usuario());

        assertThatThrownBy(() -> registro(a.ferreteria(), otroComprobanteDeB, "SIMULADOR", 1, "B"))
            .hasMessageContaining("fk_comprobante_fiscal_comprobante");
        assertThatThrownBy(() -> alicuota(a.ferreteria(), b.registro(), 4))
            .hasMessageContaining("fk_comprobante_fiscal_alicuota_comprobante_fiscal");
        assertThatThrownBy(() -> intento(a.ferreteria(), b.registro(), a.serie(), 2, "NO_LLEGO"))
            .hasMessageContaining("fk_intento_fiscal_comprobante_fiscal");
        assertThatThrownBy(() -> intento(a.ferreteria(), a.registro(), b.serie(), 2, "NO_LLEGO"))
            .hasMessageContaining("fk_intento_fiscal_serie_fiscal");
        // Dentro de la misma ferreteria, si.
        assertThatCode(() -> registro(b.ferreteria(), otroComprobanteDeB, "SIMULADOR", 1, "B")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1-1: una config_arca por ferreteria, un registro fiscal por comprobante, una serie por clave y una alicuota por Id")
    void unicosUnoAUno() {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);

        assertThatThrownBy(() -> jdbc.update("insert into config_arca (ferreteria_id) values (?)", a.ferreteria()))
            .hasMessageContaining("uk_config_arca_ferreteria");
        assertThatThrownBy(() -> registro(a.ferreteria(), a.comprobante(), "SIMULADOR", 1, "B"))
            .hasMessageContaining("uk_comprobante_fiscal_comprobante");
        assertThatThrownBy(() -> serie(a.ferreteria(), "SIMULADOR", 1, 6))
            .hasMessageContaining("uk_serie_fiscal_clave");
        assertThatThrownBy(() -> alicuota(a.ferreteria(), a.registro(), 5))
            .hasMessageContaining("uk_comprobante_fiscal_alicuota_iva");
    }

    @Test
    @DisplayName("uk_comprobante_fiscal_numero: un numero por serie, varios sin numero (NULL), y un rechazo libera el numero")
    void unNumeroPorSerie() {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);
        Escenario b = escenarioCompleto(CUIT_B);
        long segundo = registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "B");
        long tercero = registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "B");
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ? and numero is null",
            Integer.class, a.ferreteria())).isEqualTo(3);

        numerar(a.registro(), 7);
        assertThatThrownBy(() -> numerar(segundo, 7)).hasMessageContaining("uk_comprobante_fiscal_numero");
        numerar(tercero, 8);

        jdbc.update("update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', numero = null, cbte_fch = null "
            + "where id = ?", a.registro());
        assertThatCode(() -> numerar(segundo, 7)).doesNotThrowAnyException();

        // El mismo numero entra en otra serie: otro punto de venta, otra letra, otro ambiente u otra ferreteria.
        numerar(registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 2, "B"), 7);
        numerar(registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "A"), 7);
        numerar(registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "HOMOLOGACION", 1, "B"), 7);
        numerar(b.registro(), 7);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where numero = 7", Integer.class))
            .isEqualTo(5);
    }

    @ParameterizedTest(name = "con el primero en {0}, el segundo en {1}")
    @CsvSource({"ABIERTO, ABIERTO", "EN_VERIFICACION, ABIERTO", "ALERTA, ABIERTO", "ABIERTO, EN_VERIFICACION",
        "ABIERTO, ALERTA"})
    @DisplayName("uk_intento_fiscal_abierto_por_serie: a lo sumo un intento abierto (ABIERTO, EN_VERIFICACION o ALERTA) por serie")
    void unSoloIntentoAbiertoPorSerie(String estadoDelPrimero, String estadoDelSegundo) {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);
        long segundo = registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "B");
        long primero = intento(a.ferreteria(), a.registro(), a.serie(), 1, estadoDelPrimero);

        assertThatThrownBy(() -> intento(a.ferreteria(), segundo, a.serie(), 2, estadoDelSegundo))
            .hasMessageContaining("uk_intento_fiscal_abierto_por_serie");

        // Otra serie de la misma ferreteria tiene el suyo, y los cerrados no cuentan.
        long otraSerie = serie(a.ferreteria(), "SIMULADOR", 1, 1);
        long otroRegistro = registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "A");
        assertThatCode(() -> intento(a.ferreteria(), otroRegistro, otraSerie, 1, "ABIERTO")).doesNotThrowAnyException();
        assertThatCode(() -> intento(a.ferreteria(), segundo, a.serie(), 1, "NO_LLEGO")).doesNotThrowAnyException();

        // Cerrado el primero, la serie acepta el siguiente.
        jdbc.update("update intento_fiscal set estado = 'NO_LLEGO', terminado_en = coalesce(terminado_en, "
            + "'2026-10-28 13:00:01'), cerrado_en = '2026-10-28 13:00:02' where id = ?", primero);
        assertThatCode(() -> intento(a.ferreteria(), segundo, a.serie(), 1, estadoDelSegundo)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "reabrir en {0}")
    @ValueSource(strings = {"ABIERTO", "EN_VERIFICACION", "ALERTA"})
    @DisplayName("uk_intento_fiscal_abierto_por_serie: reabrir por UPDATE un intento cerrado mientras otro esta abierto falla y lo deja como estaba")
    void reabrirUnIntentoCerradoConOtroAbiertoFalla(String estado) {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);   // su intento esta cerrado (NO_LLEGO)
        long segundo = registro(a.ferreteria(), comprobante(a.ferreteria(), a.usuario()), "SIMULADOR", 1, "B");
        intento(a.ferreteria(), segundo, a.serie(), 2, "ABIERTO");

        assertThatThrownBy(() -> jdbc.update("update intento_fiscal set estado = ?, cerrado_en = null, "
            + "terminado_en = if(? = 'ABIERTO', null, terminado_en) where id = ?", estado, estado, a.intento()))
            .hasMessageContaining("uk_intento_fiscal_abierto_por_serie");
        assertThat(jdbc.queryForObject("select estado from intento_fiscal where id = ?", String.class, a.intento()))
            .isEqualTo("NO_LLEGO");
    }

    @Test
    @DisplayName("N-1 en una transaccion: el intento viejo pasa a NO_LLEGO, entra uno ABIERTO con el mismo numero y CbteFch, y el registro vuelve a EN_CURSO conservandolos")
    void reenvioPorNMenosUnoEnUnaTransaccion() throws Exception {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);
        jdbc.update("update comprobante_fiscal set estado = 'EN_VERIFICACION', numero = 5, cbte_fch = '2026-10-28' where id = ?",
            a.registro());
        long viejo = intento(a.ferreteria(), a.registro(), a.serie(), 5, "EN_VERIFICACION");

        try (Connection c = conexion()) {
            c.setAutoCommit(false);
            assertThat(actualizar(c, "update intento_fiscal set estado = 'NO_LLEGO', cerrado_en = '2026-10-28 13:05:00', "
                + "respuesta_verificacion = 'FECompUltimoAutorizado: 4' where ferreteria_id = ? and id = ? "
                + "and estado = 'EN_VERIFICACION'", a.ferreteria(), viejo)).isEqualTo(1);
            assertThat(actualizar(c, "insert into intento_fiscal (ferreteria_id, comprobante_fiscal_id, serie_fiscal_id, "
                + "numero, cbte_fch, estado, pedido, iniciado_en) select ferreteria_id, comprobante_fiscal_id, serie_fiscal_id, "
                + "numero, cbte_fch, 'ABIERTO', pedido, '2026-10-28 13:05:00' from intento_fiscal where ferreteria_id = ? "
                + "and id = ?", a.ferreteria(), viejo)).isEqualTo(1);
            assertThat(actualizar(c, "update comprobante_fiscal set estado = 'EN_CURSO' where ferreteria_id = ? and id = ? "
                + "and estado = 'EN_VERIFICACION'", a.ferreteria(), a.registro())).isEqualTo(1);
            c.commit();
        }

        assertThat(jdbc.queryForList("select estado, numero, cast(cbte_fch as char) as cbte_fch from intento_fiscal "
            + "where comprobante_fiscal_id = ? and numero = 5 order by id", a.registro()))
            .containsExactly(Map.of("estado", "NO_LLEGO", "numero", 5L, "cbte_fch", "2026-10-28"),
                Map.of("estado", "ABIERTO", "numero", 5L, "cbte_fch", "2026-10-28"));
        assertThat(jdbc.queryForMap("select estado, numero, cast(cbte_fch as char) as cbte_fch from comprobante_fiscal "
            + "where id = ?", a.registro()))
            .isEqualTo(Map.of("estado", "EN_CURSO", "numero", 5L, "cbte_fch", "2026-10-28"));
    }

    @Test
    @DisplayName("lease: dos tomas simultaneas de la serie con el UPDATE condicional, una afecta 1 fila y la otra 0, sin deadlocks")
    void dosTomasSimultaneasDelLease() throws Exception {
        migrarALa32();
        Escenario a = escenarioCompleto(CUIT_A);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbc);
        long deadlocks = barrera.deadlocksAntes();
        ExecutorService hilo = Executors.newSingleThreadExecutor();

        try (Connection uno = conexion(); Connection otro = conexion()) {
            uno.setAutoCommit(false);
            assertThat(tomarLease(uno, a, "dueno-1")).isEqualTo(1);
            Future<Integer> segunda = hilo.submit(() -> tomarLease(otro, a, "dueno-2"));
            barrera.esperarBloqueados(1);   // la segunda toma espera el lock de la fila de la serie
            assertThat(barrera.objetosEnEspera()).containsExactly("serie_fiscal");
            uno.commit();
            assertThat(segunda.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            hilo.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select lease_dueno from serie_fiscal where id = ?", String.class, a.serie()))
            .isEqualTo("dueno-1");
        barrera.assertSinDeadlocks(deadlocks);
    }

    /** El UPDATE condicional del lease, con la hora fija (13:00:00) y 30 s de vida. */
    private static int tomarLease(Connection c, Escenario a, String dueno) throws SQLException {
        return actualizar(c, "update serie_fiscal set lease_dueno = ?, lease_vence = '2026-10-28 13:00:30' "
            + "where ferreteria_id = ? and id = ? "
            + "and (lease_dueno is null or lease_vence <= '2026-10-28 13:00:00' or lease_dueno = ?)",
            dueno, a.ferreteria(), a.serie(), dueno);
    }

    private static int actualizar(Connection c, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            return ps.executeUpdate();
        }
    }

    private Connection conexion() throws SQLException {
        return DriverManager.getConnection(url, "root", MYSQL.getPassword());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        config_arca: ambiente fuera de la lista | update config_arca set ambiente = 'PRUEBA' | ck_config_arca_ambiente
        config_arca: punto de venta 0 | update config_arca set ambiente = 'HOMOLOGACION', punto_venta = 0 | ck_config_arca_punto_venta
        config_arca: punto de venta de 6 cifras | update config_arca set ambiente = 'HOMOLOGACION', punto_venta = 100000 | ck_config_arca_punto_venta
        config_arca: SIMULADOR sin la marca de prueba | update config_arca set de_prueba = false | ck_config_arca_simulador_de_prueba
        config_arca: SIMULADOR con punto de venta 2 | update config_arca set punto_venta = 2 | ck_config_arca_simulador_punto_venta
        config_arca: SIMULADOR sin punto de venta | update config_arca set facturacion_habilitada = false, habilitada_en = null, punto_venta = null | ck_config_arca_simulador_punto_venta
        config_arca: habilitada sin ambiente | update config_arca set ambiente = null | ck_config_arca_habilitada
        config_arca: habilitada sin punto de venta | update config_arca set ambiente = 'HOMOLOGACION', punto_venta = null | ck_config_arca_habilitada
        config_arca: habilitada sin fecha | update config_arca set habilitada_en = null | ck_config_arca_habilitada
        config_arca: fecha sin estar habilitada | update config_arca set facturacion_habilitada = false | ck_config_arca_habilitada
        registro: ambiente fuera de la lista | update comprobante_fiscal set ambiente = 'PRUEBA' | ck_comprobante_fiscal_ambiente
        registro: letra X | update comprobante_fiscal set letra = 'X' | ck_comprobante_fiscal_tipo
        registro: CbteTipo que no es de la letra | update comprobante_fiscal set cbte_tipo = 1 | ck_comprobante_fiscal_tipo
        registro: clase fuera de la lista | update comprobante_fiscal set clase = 'RECIBO' | ck_comprobante_fiscal_tipo
        registro: punto de venta 0 | update comprobante_fiscal set punto_venta = 0 | ck_comprobante_fiscal_numeracion
        registro: numero 0 | update comprobante_fiscal set estado = 'EN_CURSO', numero = 0, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_numeracion
        registro: numero de 9 cifras | update comprobante_fiscal set estado = 'EN_CURSO', numero = 100000000, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_numeracion
        registro: estado fuera de la lista | update comprobante_fiscal set estado = 'ANULADO', numero = 1, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_estado
        registro: DocTipo fuera de la lista | update comprobante_fiscal set receptor_doc_tipo = 87 | ck_comprobante_fiscal_receptor
        registro: CondicionIVAReceptorId fuera de la lista | update comprobante_fiscal set receptor_condicion_iva_id = 13 | ck_comprobante_fiscal_receptor
        registro: DocNro negativo | update comprobante_fiscal set receptor_doc_nro = -1 | ck_comprobante_fiscal_receptor
        registro: condicion de IVA del emisor fuera de la lista | update comprobante_fiscal set emisor_condicion_iva = 'CONSUMIDOR_FINAL' | ck_comprobante_fiscal_emisor
        registro: ImpTotal distinto de la suma | update comprobante_fiscal set imp_total = 120 | ck_comprobante_fiscal_importes
        registro: un importe negativo aunque sume | update comprobante_fiscal set imp_neto = -100, imp_iva = 221 | ck_comprobante_fiscal_importes
        coherencia: numero sin CbteFch | update comprobante_fiscal set estado = 'EN_CURSO', numero = 1 | ck_comprobante_fiscal_coherencia
        coherencia: CbteFch sin numero | update comprobante_fiscal set estado = 'EN_CURSO', cbte_fch = '2026-10-28' | ck_comprobante_fiscal_coherencia
        coherencia: PENDIENTE con numero | update comprobante_fiscal set numero = 1, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_coherencia
        coherencia: RECHAZADO con numero | update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', numero = 1, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_coherencia
        coherencia: FUERA_DE_FECHA con numero | update comprobante_fiscal set estado = 'FUERA_DE_FECHA', numero = 1, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_coherencia
        coherencia: EN_CURSO sin numero | update comprobante_fiscal set estado = 'EN_CURSO' | ck_comprobante_fiscal_coherencia
        coherencia: EN_VERIFICACION sin numero | update comprobante_fiscal set estado = 'EN_VERIFICACION' | ck_comprobante_fiscal_coherencia
        coherencia: CAE_NO_COINCIDE sin numero | update comprobante_fiscal set estado = 'CAE_NO_COINCIDE' | ck_comprobante_fiscal_coherencia
        coherencia: SERIE_DESINCRONIZADA sin numero | update comprobante_fiscal set estado = 'SERIE_DESINCRONIZADA' | ck_comprobante_fiscal_coherencia
        coherencia: AUTORIZADO sin CAE | update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', numero = 1, cbte_fch = '2026-10-28', cae_vencimiento = '2026-11-07' | ck_comprobante_fiscal_coherencia
        coherencia: AUTORIZADO sin vencimiento | update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', numero = 1, cbte_fch = '2026-10-28', cae = '76432100123456' | ck_comprobante_fiscal_coherencia
        coherencia: AUTORIZADO sin numero ni CbteFch | update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', cae = '76432100123456', cae_vencimiento = '2026-11-07' | ck_comprobante_fiscal_coherencia
        coherencia: AUTORIZADO sin resultado A | update comprobante_fiscal set estado = 'AUTORIZADO', numero = 1, cbte_fch = '2026-10-28', cae = '76432100123456', cae_vencimiento = '2026-11-07' | ck_comprobante_fiscal_coherencia
        coherencia: CAE fuera de AUTORIZADO | update comprobante_fiscal set estado = 'EN_CURSO', numero = 1, cbte_fch = '2026-10-28', cae = '76432100123456', cae_vencimiento = '2026-11-07' | ck_comprobante_fiscal_coherencia
        coherencia: RECHAZADO sin resultado R | update comprobante_fiscal set estado = 'RECHAZADO' | ck_comprobante_fiscal_coherencia
        coherencia: resultado A sin autorizar | update comprobante_fiscal set resultado = 'A' | ck_comprobante_fiscal_coherencia
        alicuota: Id de IVA fuera de la lista | update comprobante_fiscal_alicuota set iva_id = 7 | ck_comprobante_fiscal_alicuota_valores
        alicuota: BaseImp negativa | update comprobante_fiscal_alicuota set base_imp = -100 | ck_comprobante_fiscal_alicuota_valores
        alicuota: Importe negativo | update comprobante_fiscal_alicuota set importe = -21 | ck_comprobante_fiscal_alicuota_valores
        intento: estado fuera de la lista | update intento_fiscal set estado = 'CERRADO' | ck_intento_fiscal_estado
        intento: ABIERTO ya terminado | update intento_fiscal set estado = 'ABIERTO', cerrado_en = null | ck_intento_fiscal_coherencia
        intento: EN_VERIFICACION cerrado | update intento_fiscal set estado = 'EN_VERIFICACION' | ck_intento_fiscal_coherencia
        intento: ALERTA cerrada | update intento_fiscal set estado = 'ALERTA' | ck_intento_fiscal_coherencia
        intento: NO_LLEGO sin cerrar | update intento_fiscal set cerrado_en = null | ck_intento_fiscal_coherencia
        intento: NO_LLEGO sin terminar | update intento_fiscal set terminado_en = null | ck_intento_fiscal_coherencia
        intento: AUTORIZADO sin resultado A | update intento_fiscal set estado = 'AUTORIZADO' | ck_intento_fiscal_coherencia
        intento: RECHAZADO con resultado A | update intento_fiscal set estado = 'RECHAZADO', resultado = 'A' | ck_intento_fiscal_coherencia
        intento: resultado R en NO_LLEGO | update intento_fiscal set resultado = 'R' | ck_intento_fiscal_coherencia
        serie: ambiente fuera de la lista | update serie_fiscal set ambiente = 'PRUEBA' | ck_serie_fiscal_clave
        serie: CbteTipo fuera de la lista | update serie_fiscal set cbte_tipo = 2 | ck_serie_fiscal_clave
        serie: punto de venta 0 | update serie_fiscal set punto_venta = 0 | ck_serie_fiscal_clave
        serie: dueno del lease sin vencimiento | update serie_fiscal set lease_dueno = 'instancia-1' | ck_serie_fiscal_lease
        serie: vencimiento del lease sin dueno | update serie_fiscal set lease_vence = '2026-10-28 13:00:30' | ck_serie_fiscal_lease
        serie: ultimo autorizado negativo | update serie_fiscal set ultimo_autorizado = -1 | ck_serie_fiscal_ultimo
        serie: CbteFch sin ultimo autorizado | update serie_fiscal set ultima_cbte_fch = '2026-10-28' | ck_serie_fiscal_ultimo
        serie: CbteFch con ultimo autorizado 0 | update serie_fiscal set ultimo_autorizado = 0, ultima_cbte_fch = '2026-10-28' | ck_serie_fiscal_ultimo
        """)
    @DisplayName("cada CHECK rechaza lo incoherente (estados, coherencia del registro, SIMULADOR solo de prueba, habilitada con datos)")
    void cadaCheckRechazaLoIncoherente(String caso, String sentencia, String restriccion) {
        migrarALa32();
        escenarioCompleto(CUIT_A);

        assertThatThrownBy(() -> jdbc.update(sentencia)).as(caso).hasMessageContaining(restriccion);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        config_arca: recien creada, sin nada | update config_arca set de_prueba = false, ambiente = null, punto_venta = null, facturacion_habilitada = false, habilitada_en = null
        config_arca: PRODUCCION sin la marca de prueba | update config_arca set ambiente = 'PRODUCCION', de_prueba = false
        config_arca: HOMOLOGACION con punto de venta 99999 | update config_arca set ambiente = 'HOMOLOGACION', punto_venta = 99999
        registro: EN_CURSO con numero y CbteFch | update comprobante_fiscal set estado = 'EN_CURSO', numero = 1, cbte_fch = '2026-10-28'
        registro: EN_VERIFICACION con numero y CbteFch | update comprobante_fiscal set estado = 'EN_VERIFICACION', numero = 99999999, cbte_fch = '2026-10-28'
        registro: AUTORIZADO con CAE, vencimiento, numero, CbteFch y resultado A | update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', numero = 1, cbte_fch = '2026-10-28', cae = '76432100123456', cae_vencimiento = '2026-11-07', observaciones = json_array(json_object('Code', 10063, 'Msg', 'Obs'))
        registro: RECHAZADO sin numero, con resultado R y errores | update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', errores = json_array(json_object('Code', 10016, 'Msg', 'Err'))
        registro: FUERA_DE_FECHA sin numero | update comprobante_fiscal set estado = 'FUERA_DE_FECHA'
        registro: CAE_NO_COINCIDE con su numero y sin CAE | update comprobante_fiscal set estado = 'CAE_NO_COINCIDE', numero = 1, cbte_fch = '2026-10-28'
        registro: SERIE_DESINCRONIZADA con su numero y sin CAE | update comprobante_fiscal set estado = 'SERIE_DESINCRONIZADA', numero = 1, cbte_fch = '2026-10-28'
        registro: nota de credito C (CbteTipo 13) | update comprobante_fiscal set clase = 'NOTA_CREDITO', letra = 'C', cbte_tipo = 13
        registro: receptor con CUIT y RI | update comprobante_fiscal set receptor_doc_tipo = 80, receptor_doc_nro = 30786013212, receptor_condicion_iva_id = 1, receptor_nombre = 'Cliente RI', receptor_domicilio = 'Av. Luro 3200'
        intento: ABIERTO sin terminar ni cerrar | update intento_fiscal set estado = 'ABIERTO', terminado_en = null, cerrado_en = null
        intento: EN_VERIFICACION terminado y sin cerrar | update intento_fiscal set estado = 'EN_VERIFICACION', cerrado_en = null, respuesta = 'timeout de lectura'
        intento: ALERTA terminada y sin cerrar | update intento_fiscal set estado = 'ALERTA', cerrado_en = null, respuesta_verificacion = 'FECompConsultar'
        intento: AUTORIZADO con resultado A | update intento_fiscal set estado = 'AUTORIZADO', resultado = 'A'
        intento: RECHAZADO con resultado R | update intento_fiscal set estado = 'RECHAZADO', resultado = 'R'
        serie: con lease y ultimo autorizado | update serie_fiscal set lease_dueno = 'instancia-1', lease_vence = '2026-10-28 13:00:30', ultimo_autorizado = 7, ultima_cbte_fch = '2026-10-28'
        serie: recien consultada, ultimo 0 sin CbteFch | update serie_fiscal set ultimo_autorizado = 0
        """)
    @DisplayName("cada estado coherente entra: los CHECK no frenan lo que el motor necesita guardar")
    void cadaEstadoCoherenteEntra(String caso, String sentencia) {
        migrarALa32();
        escenarioCompleto(CUIT_A);

        assertThat(jdbc.update(sentencia)).as(caso).isEqualTo(1);
    }

    @Test
    @DisplayName("aditiva: ninguna tabla existente cambia (columnas, restricciones ni indices), sus filas quedan igual y no se pierde ninguna migracion")
    void noQuitaNada() {
        long f = ferreteria(CUIT_A);
        long comprobante = comprobante(f, usuario(f));
        Map<String, Object> ferreteriaAntes = jdbc.queryForMap("select * from ferreteria where id = ?", f);
        Map<String, Object> comprobanteAntes = jdbc.queryForMap("select * from comprobante where id = ?", comprobante);
        List<String> esquemaAntes = esquema();

        migrarALa32();

        List<String> esquemaDespues = esquema();
        assertThat(esquemaDespues.stream().filter(fila -> !TABLAS_NUEVAS.contains(fila.split(" ")[0])).toList())
            .containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(esquemaDespues.stream().map(fila -> fila.split(" ")[0]).distinct()
            .filter(tabla -> esquemaAntes.stream().noneMatch(fila -> fila.startsWith(tabla + " "))).toList())
            .containsExactlyInAnyOrderElementsOf(TABLAS_NUEVAS);
        assertThat(jdbc.queryForMap("select * from ferreteria where id = ?", f)).isEqualTo(ferreteriaAntes);
        assertThat(jdbc.queryForMap("select * from comprobante where id = ?", comprobante)).isEqualTo(comprobanteAntes);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(32);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V32 entra por out-of-order, las ventas quedan igual y las tablas nuevas vacias")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String ferreterias = "select id, razon_social, cuit, condicion_iva from ferreteria order by id";
        String comprobantes = "select id, ferreteria_id, numero, estado, total from comprobante order by id";
        List<Map<String, Object>> ferreteriasAntes = jdbc.queryForList(ferreterias);
        List<Map<String, Object>> comprobantesAntes = jdbc.queryForList(comprobantes);
        assertThat(ferreteriasAntes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '32' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(ferreterias)).isEqualTo(ferreteriasAntes);
        assertThat(jdbc.queryForList(comprobantes)).isEqualTo(comprobantesAntes);
        assertThat(TABLAS_NUEVAS).allSatisfy(tabla ->
            assertThat(jdbc.queryForObject("select count(*) from " + tabla, Integer.class)).as(tabla).isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v32-facturacion.sql, sin datos, saca las cinco tablas y la V32, deja el esquema como en la V31, y la V32 vuelve a entrar")
    void rollbackSinDatos(String modo) throws Exception {
        long f = ferreteria(CUIT_A);
        List<String> esquemaAntes = esquema();
        migrarALa32();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '32'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, f))
            .isEqualTo("Ferreteria " + CUIT_A);
        // Y V32 se puede volver a aplicar.
        migrarALa32();
        assertThat(columnas("intento_fiscal")).isNotEmpty();
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"config_arca", "serie_fiscal", "comprobante_fiscal", "comprobante_fiscal_alicuota",
        "intento_fiscal"})
    @DisplayName("rollback-v32-facturacion.sql se niega si hay una sola fila en las tablas de facturacion, y no toca nada")
    void rollbackConDatosSeNiega(String tabla) {
        migrarALa32();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDeFacturacion();

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_datos_de_facturacion");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDeFacturacion()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '32'", Integer.class))
            .isEqualTo(1);
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"config_arca", "serie_fiscal", "comprobante_fiscal", "comprobante_fiscal_alicuota",
        "intento_fiscal"})
    @DisplayName("rollback-v32-facturacion.sql corrido sentencia por sentencia ignorando errores (mysql --force, consola) "
        + "con una sola fila no borra ninguna tabla ni la 32 del historial")
    void rollbackIgnorandoErroresConDatosNoBorraNada(String tabla) throws Exception {
        migrarALa32();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDeFacturacion();

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_sin_datos_de_facturacion"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDeFacturacion()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '32'", Integer.class))
            .isEqualTo(1);
    }

    /** Una sola fila en la tabla pedida, mas los padres que necesite (la alicuota y el intento cuelgan de un registro). */
    private void sembrarUnaFilaEn(String tabla) {
        long f = ferreteria(CUIT_A);
        switch (tabla) {
            case "config_arca" -> jdbc.update("insert into config_arca (ferreteria_id, de_prueba) values (?, true)", f);
            case "serie_fiscal" -> serie(f, "SIMULADOR", 1, 6);
            case "comprobante_fiscal" -> registro(f, comprobante(f, usuario(f)), "SIMULADOR", 1, "B");
            case "comprobante_fiscal_alicuota" -> alicuota(f, registro(f, comprobante(f, usuario(f)), "SIMULADOR", 1, "B"), 5);
            default -> intento(f, registro(f, comprobante(f, usuario(f)), "SIMULADOR", 1, "B"), serie(f, "SIMULADOR", 1, 6),
                1, "NO_LLEGO");
        }
    }

    private Map<String, Integer> filasDeFacturacion() {
        Map<String, Integer> filas = new HashMap<>();
        TABLAS_NUEVAS.forEach(t -> filas.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class)));
        return filas;
    }

    // ---- siembra por JDBC ----

    private record Escenario(long ferreteria, long usuario, long comprobante, long registro, long serie, long intento) {}

    /** Una fila valida en cada tabla nueva: SIMULADOR, punto de venta 1, Factura B; el intento ya cerrado (NO_LLEGO). */
    private Escenario escenarioCompleto(String cuit) {
        long f = ferreteria(cuit);
        long usuario = usuario(f);
        long comprobante = comprobante(f, usuario);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
            + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-28 13:00:00')", f);
        long serie = serie(f, "SIMULADOR", 1, 6);
        long registro = registro(f, comprobante, "SIMULADOR", 1, "B");
        alicuota(f, registro, 5);
        long intento = intento(f, registro, serie, 1, "NO_LLEGO");
        return new Escenario(f, usuario, comprobante, registro, serie, intento);
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private long usuario(long ferreteria) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V32', 'DUENO')",
            ferreteria, "v32-" + ferreteria + "@test.local");
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private long comprobante(long ferreteria, long usuario) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
            ferreteria, numero, "v32-" + numero, usuario);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            ferreteria, numero);
    }

    private long serie(long ferreteria, String ambiente, int puntoVenta, int cbteTipo) {
        jdbc.update("insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo) values (?, ?, ?, ?)",
            ferreteria, ambiente, puntoVenta, cbteTipo);
        return jdbc.queryForObject("select id from serie_fiscal where ferreteria_id = ? and ambiente = ? and punto_venta = ? "
            + "and cbte_tipo = ?", Long.class, ferreteria, ambiente, puntoVenta, cbteTipo);
    }

    /** Un registro PENDIENTE de una Factura de la letra pedida, a Consumidor Final, por $ 121 al 21 %. */
    private long registro(long ferreteria, long comprobante, String ambiente, int puntoVenta, String letra) {
        int cbteTipo = switch (letra) {
            case "A" -> 1;
            case "B" -> 6;
            default -> 11;
        };
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
            + "punto_venta, estado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, "
            + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
            + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
            + "values (?, ?, ?, 'FACTURA', ?, ?, ?, 'PENDIENTE', 99, 0, 5, '30-78601320-4', 'Ferreteria V32', "
            + "'RESPONSABLE_INSCRIPTO', 'Av. Independencia 1234, Mar del Plata', '901-123456-7', '2015-03-01', "
            + "121.00, 100.00, 21.00, 0, 0, '2026-10-28 13:00:00')",
            ferreteria, comprobante, ambiente, letra, cbteTipo, puntoVenta);
        return jdbc.queryForObject("select id from comprobante_fiscal where ferreteria_id = ? and comprobante_id = ?",
            Long.class, ferreteria, comprobante);
    }

    private void alicuota(long ferreteria, long registro, int ivaId) {
        jdbc.update("insert into comprobante_fiscal_alicuota (ferreteria_id, comprobante_fiscal_id, iva_id, base_imp, importe) "
            + "values (?, ?, ?, 100.00, 21.00)", ferreteria, registro, ivaId);
    }

    /** Un intento en el estado pedido, con las horas y el resultado que ese estado exige. */
    private long intento(long ferreteria, long registro, long serie, long numero, String estado) {
        boolean terminado = !estado.equals("ABIERTO");
        boolean cerrado = !List.of("ABIERTO", "EN_VERIFICACION", "ALERTA").contains(estado);
        String resultado = switch (estado) {
            case "AUTORIZADO" -> "A";
            case "RECHAZADO" -> "R";
            default -> null;
        };
        jdbc.update("insert into intento_fiscal (ferreteria_id, comprobante_fiscal_id, serie_fiscal_id, numero, cbte_fch, "
            + "estado, resultado, pedido, iniciado_en, terminado_en, cerrado_en) "
            + "values (?, ?, ?, ?, '2026-10-28', ?, ?, 'FECAESolicitar', '2026-10-28 13:00:00', ?, ?)",
            ferreteria, registro, serie, numero, estado, resultado, terminado ? "2026-10-28 13:00:01" : null,
            cerrado ? "2026-10-28 13:00:02" : null);
        return jdbc.queryForObject("select max(id) from intento_fiscal where ferreteria_id = ?", Long.class, ferreteria);
    }

    private void numerar(long registro, long numero) {
        jdbc.update("update comprobante_fiscal set estado = 'EN_CURSO', numero = ?, cbte_fch = '2026-10-28' where id = ?",
            numero, registro);
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo" de la tabla. */
    private Map<String, String> columnas(String tabla) {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo "
            + "from information_schema.columns where table_schema = database() and table_name = ?", tabla)
            .forEach(c -> columnas.put((String) c.get("nombre"), c.get("tipo") + " " + c.get("nulo")));
        return columnas;
    }

    /** "nombre tipo nulo" -> mapa columna -> "tipo nulo". */
    private static Map<String, String> esperadas(String... columnas) {
        Map<String, String> esperadas = new HashMap<>();
        for (String columna : columnas) {
            int espacio = columna.indexOf(' ');
            esperadas.put(columna.substring(0, espacio), columna.substring(espacio + 1));
        }
        return esperadas;
    }

    /** Todo el esquema, una fila por columna, restriccion, CHECK e indice; cada fila empieza por el nombre de su tabla. */
    private List<String> esquema() {
        List<String> filas = new ArrayList<>();
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'columna', column_name, column_type, is_nullable, "
            + "coalesce(column_default, '-'), extra) from information_schema.columns where table_schema = database()",
            String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, constraint_type, constraint_name) "
            + "from information_schema.table_constraints where constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', t.table_name, 'check', c.constraint_name, c.check_clause) "
            + "from information_schema.check_constraints c join information_schema.table_constraints t "
            + "on t.constraint_schema = c.constraint_schema and t.constraint_name = c.constraint_name "
            + "and t.constraint_type = 'CHECK' where c.constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'indice', index_name, non_unique, seq_in_index, "
            + "coalesce(column_name, expression)) from information_schema.statistics where table_schema = database()",
            String.class));
        return filas;
    }

    private void correrRollback() throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }
    }

    /**
     * Como un mysql --force o el script pegado en una consola: sentencia por sentencia (sin los comentarios, cortando en cada
     * punto y coma), sigue aunque una falle. Devuelve los mensajes de las que fallaron.
     */
    private List<String> correrRollbackIgnorandoErrores() throws Exception {
        String sinComentarios = scriptDeRollback().lines().filter(linea -> !linea.strip().startsWith("--"))
            .collect(Collectors.joining("\n"));
        List<String> errores = new ArrayList<>();
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            for (String sentencia : sinComentarios.split(";")) {
                if (sentencia.isBlank()) {
                    continue;
                }
                try {
                    s.execute(sentencia);
                } catch (SQLException e) {
                    errores.add(e.getMessage());
                }
            }
        }
        return errores;
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("facturacion/rollback-v32-facturacion.sql").getInputStream(), StandardCharsets.UTF_8);
    }
}
