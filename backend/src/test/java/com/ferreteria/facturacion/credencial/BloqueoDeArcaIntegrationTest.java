package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.tenant.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * C4 y S3: el bloqueo de ARCA (WSAA rechazó el certificado) se levanta al activar otro certificado, en la misma transacción
 * en que se activa, y con {@link CredencialArcaService#levantarBloqueo()}, el que usa «Probar conexión» OK (T7). El bloqueo
 * lo pone el ticket de acceso; acá se deja por SQL. Cada test usa su ferretería; otra ferretería no se toca.
 */
class BloqueoDeArcaIntegrationTest extends CredencialArcaTestBase {

    @Autowired
    CredencialArcaService servicio;

    private void bloqueada(Ferreteria f) {
        jdbc.update("update config_arca set bloqueo_codigo = 'cms.cert.untrusted', bloqueo_mensaje = 'Certificado no "
                + "emitido por AC de confianza', bloqueado_en = '2026-10-28 13:10:00' where ferreteria_id = ?", f.id());
    }

    private Map<String, Object> bloqueo(Ferreteria f) {
        return jdbc.queryForMap("select bloqueo_codigo, bloqueo_mensaje, bloqueado_en from config_arca where ferreteria_id = ?",
                f.id());
    }

    @Test
    @DisplayName("activar otro certificado levanta el bloqueo de ARCA; el de otra ferretería sigue")
    void activarOtroCertificadoLevantaElBloqueo() throws Exception {
        Ferreteria f = ferreteria();
        Ferreteria otra = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        activa(otra, CasDeArcaDePrueba.HOMOLOGACION);
        bloqueada(f);
        bloqueada(otra);

        activa(f, CasDeArcaDePrueba.HOMOLOGACION);

        assertThat(bloqueo(f)).containsEntry("bloqueo_codigo", null).containsEntry("bloqueo_mensaje", null)
                .containsEntry("bloqueado_en", null);
        assertThat(bloqueo(otra)).containsEntry("bloqueo_codigo", "cms.cert.untrusted");
    }

    @Test
    @DisplayName("levantarBloqueo (lo que hace «Probar conexión» OK) lo levanta, solo en la ferretería del request")
    void levantarBloqueoDeLaFerreteriaDelRequest() throws Exception {
        Ferreteria f = ferreteria();
        Ferreteria otra = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        activa(otra, CasDeArcaDePrueba.HOMOLOGACION);
        bloqueada(f);
        bloqueada(otra);

        TenantContext.setCurrentFerreteriaId(f.id());
        try {
            servicio.levantarBloqueo();
        } finally {
            TenantContext.clear();
        }

        assertThat(bloqueo(f)).containsEntry("bloqueo_codigo", null).containsEntry("bloqueado_en", null);
        assertThat(bloqueo(otra)).containsEntry("bloqueo_codigo", "cms.cert.untrusted");
    }
}
