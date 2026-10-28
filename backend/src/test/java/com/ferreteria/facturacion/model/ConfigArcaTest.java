package com.ferreteria.facturacion.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ReglaDeNegocioException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CU-01 «Ambiente de ARCA», «Punto de venta con el simulador» y «Facturación habilitada» (D3, S2 y S3). Sin
 * {@code @Nested} a propósito: con un selector de método en {@code -Dtest}, surefire no corre las clases anidadas.
 */
class ConfigArcaTest {

    private static final Instant AHORA = Instant.parse("2026-10-28T13:00:00Z");
    private static final RegistrosFiscales ABIERTOS = new RegistrosFiscales(true, false);
    private static final RegistrosFiscales REALES = new RegistrosFiscales(false, true);
    private static final String CON_REGISTROS_ABIERTOS = "La ferretería tiene comprobantes fiscales sin terminar: el "
            + "ambiente no se puede cambiar hasta que se resuelvan.";
    private static final String AMBIENTE_DEL_CERTIFICADO = "El ambiente de homologación o producción lo fija el "
            + "certificado: no se cambia desde el panel.";

    private static ConfigArca dePrueba() {
        ConfigArca config = ConfigArca.nueva(7L);
        config.cambiarDePrueba(true);
        return config;
    }

    private static ConfigArca conSimulador() {
        ConfigArca config = dePrueba();
        config.asignarSimulador(RegistrosFiscales.NINGUNO);
        return config;
    }

    /** Homologación o producción: en esta rama solo llegan por la base (los fija el certificado, rama 5). */
    private static ConfigArca conAmbienteDelCertificado(Ambiente ambiente) {
        ConfigArca config = ConfigArca.nueva(7L);
        ReflectionTestUtils.setField(config, "ambiente", ambiente);
        ReflectionTestUtils.setField(config, "puntoVenta", 3);
        return config;
    }

    private static void assertRegla(Runnable accion, String mensaje) {
        assertThatThrownBy(accion::run).isInstanceOf(ReglaDeNegocioException.class).hasMessage(mensaje);
    }

    @Test
    @DisplayName("una ferretería nueva no tiene la marca, ni ambiente, ni punto de venta, y no factura")
    void nueva() {
        ConfigArca config = ConfigArca.nueva(7L);

        assertThat(config.getFerreteriaId()).isEqualTo(7L);
        assertThat(config.isDePrueba()).isFalse();
        assertThat(config.getAmbiente()).isNull();
        assertThat(config.getPuntoVenta()).isNull();
        assertThat(config.isFacturacionHabilitada()).isFalse();
        assertThat(config.getHabilitadaEn()).isNull();
    }

    // ---- marca «de prueba» ----

    @Test
    @DisplayName("la marca se pone y se saca, y no toca nada más")
    void laMarcaSePoneYSeSaca() {
        ConfigArca config = ConfigArca.nueva(7L);

        config.cambiarDePrueba(true);
        assertThat(config.isDePrueba()).isTrue();

        config.cambiarDePrueba(false);
        assertThat(config.isDePrueba()).isFalse();
        assertThat(config.getAmbiente()).isNull();
        assertThat(config.isFacturacionHabilitada()).isFalse();
    }

    @Test
    @DisplayName("la marca no se saca mientras el ambiente sea el simulador")
    void laMarcaNoSeSacaConElSimulador() {
        ConfigArca config = conSimulador();

        assertRegla(() -> config.cambiarDePrueba(false), "No se puede sacar la marca «de prueba» mientras la "
                + "ferretería use el simulador: quitale el simulador primero.");
        assertThat(config.isDePrueba()).isTrue();
    }

    // ---- asignar el simulador ----

    @Test
    @DisplayName("asignar el simulador deja el ambiente en SIMULADOR y el punto de venta en 1 (S3)")
    void asignarDejaElPuntoDeVentaEn1() {
        ConfigArca config = dePrueba();

        config.asignarSimulador(RegistrosFiscales.NINGUNO);

        assertThat(config.getAmbiente()).isEqualTo(Ambiente.SIMULADOR);
        assertThat(config.getPuntoVenta()).isEqualTo(1);
        assertThat(config.isFacturacionHabilitada()).isFalse();
    }

    @Test
    @DisplayName("asignar el simulador exige la marca «de prueba»")
    void asignarExigeLaMarca() {
        ConfigArca config = ConfigArca.nueva(7L);

        assertRegla(() -> config.asignarSimulador(RegistrosFiscales.NINGUNO),
                "El simulador solo se asigna a una ferretería marcada «de prueba».");
        assertThat(config.getAmbiente()).isNull();
        assertThat(config.getPuntoVenta()).isNull();
    }

    @Test
    @DisplayName("asignar el simulador no entra con registros de homologación o producción")
    void asignarNoConRegistrosReales() {
        ConfigArca config = dePrueba();

        assertRegla(() -> config.asignarSimulador(REALES), "La ferretería ya tiene comprobantes de homologación o "
                + "producción: no se le puede asignar el simulador.");
        assertThat(config.getAmbiente()).isNull();
    }

    @Test
    @DisplayName("asignar el simulador no entra con registros abiertos")
    void asignarNoConRegistrosAbiertos() {
        ConfigArca config = dePrueba();

        assertRegla(() -> config.asignarSimulador(ABIERTOS), CON_REGISTROS_ABIERTOS);
        assertThat(config.getAmbiente()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Ambiente.class, names = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("asignar el simulador no reemplaza el ambiente que fija el certificado")
    void asignarNoReemplazaElDelCertificado(Ambiente ambiente) {
        ConfigArca config = conAmbienteDelCertificado(ambiente);
        config.cambiarDePrueba(true);

        assertRegla(() -> config.asignarSimulador(RegistrosFiscales.NINGUNO), AMBIENTE_DEL_CERTIFICADO);
        assertThat(config.getAmbiente()).isEqualTo(ambiente);
        assertThat(config.getPuntoVenta()).isEqualTo(3);
    }

    // ---- quitar el ambiente ----

    @Test
    @DisplayName("quitar el simulador deja la ferretería sin ambiente ni punto de venta, con la marca")
    void quitarElSimulador() {
        ConfigArca config = conSimulador();

        config.quitarAmbiente(RegistrosFiscales.NINGUNO);

        assertThat(config.getAmbiente()).isNull();
        assertThat(config.getPuntoVenta()).isNull();
        assertThat(config.isDePrueba()).isTrue();
    }

    @Test
    @DisplayName("quitar el ambiente exige la facturación apagada")
    void quitarExigeLaFacturacionApagada() {
        ConfigArca config = conSimulador();
        config.habilitar(List.of(), AHORA);

        assertRegla(() -> config.quitarAmbiente(RegistrosFiscales.NINGUNO), "Primero deshabilitá la facturación: "
                + "no se puede quitar el ambiente mientras esté habilitada.");
        assertThat(config.getAmbiente()).isEqualTo(Ambiente.SIMULADOR);
    }

    @Test
    @DisplayName("quitar el ambiente no sale con registros abiertos")
    void quitarNoConRegistrosAbiertos() {
        ConfigArca config = conSimulador();

        assertRegla(() -> config.quitarAmbiente(ABIERTOS), CON_REGISTROS_ABIERTOS);
        assertThat(config.getAmbiente()).isEqualTo(Ambiente.SIMULADOR);
        assertThat(config.getPuntoVenta()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Ambiente.class, names = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("no se quita el ambiente que fija el certificado")
    void quitarNoQuitaElDelCertificado(Ambiente ambiente) {
        ConfigArca config = conAmbienteDelCertificado(ambiente);

        assertRegla(() -> config.quitarAmbiente(RegistrosFiscales.NINGUNO), AMBIENTE_DEL_CERTIFICADO);
        assertThat(config.getAmbiente()).isEqualTo(ambiente);
    }

    // ---- facturación habilitada ----

    @Test
    @DisplayName("la facturación se prende con ambiente, punto de venta y los datos completos, y guarda la hora que le pasan")
    void habilitar() {
        ConfigArca config = conSimulador();

        config.habilitar(List.of(), AHORA);

        assertThat(config.isFacturacionHabilitada()).isTrue();
        assertThat(config.getHabilitadaEn()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("habilitar exige un ambiente y un punto de venta")
    void habilitarExigeAmbiente() {
        ConfigArca config = dePrueba();

        assertRegla(() -> config.habilitar(List.of(), AHORA),
                "Para habilitar la facturación, la ferretería necesita un ambiente de ARCA y un punto de venta.");
        assertThat(config.isFacturacionHabilitada()).isFalse();
        assertThat(config.getHabilitadaEn()).isNull();
    }

    @Test
    @DisplayName("habilitar exige los datos de CU-01 completos y nombra los que faltan")
    void habilitarExigeLosDatos() {
        ConfigArca config = conSimulador();

        assertRegla(() -> config.habilitar(List.of("domicilio comercial"), AHORA), "Para habilitar la facturación "
                + "falta un dato de la ferretería: domicilio comercial. Lo carga el Dueño en «Datos de la ferretería».");
        assertRegla(() -> config.habilitar(List.of("domicilio comercial", "inicio de actividades", "Ingresos Brutos"),
                AHORA), "Para habilitar la facturación faltan datos de la ferretería: domicilio comercial, inicio de "
                + "actividades e Ingresos Brutos. Los carga el Dueño en «Datos de la ferretería».");
        assertThat(config.isFacturacionHabilitada()).isFalse();
        assertThat(config.getHabilitadaEn()).isNull();
    }

    @Test
    @DisplayName("prender la facturación de nuevo no cambia la fecha de activación")
    void habilitarDeNuevoNoCambiaLaFecha() {
        ConfigArca config = conSimulador();
        config.habilitar(List.of(), AHORA);

        config.habilitar(List.of(), AHORA.plusSeconds(60));

        assertThat(config.getHabilitadaEn()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("apagar la facturación siempre se puede y borra la fecha")
    void deshabilitar() {
        ConfigArca config = conSimulador();
        config.habilitar(List.of(), AHORA);

        config.deshabilitar();

        assertThat(config.isFacturacionHabilitada()).isFalse();
        assertThat(config.getHabilitadaEn()).isNull();
        ConfigArca nueva = ConfigArca.nueva(8L);
        nueva.deshabilitar();
        assertThat(nueva.isFacturacionHabilitada()).isFalse();
    }
}
