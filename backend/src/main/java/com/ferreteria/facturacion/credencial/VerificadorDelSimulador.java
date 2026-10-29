package com.ferreteria.facturacion.credencial;

import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que hace WSAA con el CMS de {@code loginCms}, del lado del simulador en el borde (rama 6, S6): lo verifica contra la CA
 * del simulador de la ferretería del request ({@code TenantContext}), como ARCA lo verifica contra las suyas. La verificación
 * es {@link FirmaCms#verificar}; esta clase solo pone la CA. Sin tipos de BouncyCastle en su API, así el simulador la usa sin
 * depender de él.
 */
@Component
public class VerificadorDelSimulador {

    private final CaSimuladorRepository cas;

    public VerificadorDelSimulador(CaSimuladorRepository cas) {
        this.cas = cas;
    }

    /**
     * Verifica el CMS contra la CA del simulador de la ferretería del request. No mira vigencias: las decide quien llama, con su
     * {@code Clock}.
     *
     * @param cmsBase64 el {@code in0} de {@code loginCms}
     * @return el contenido firmado (el TRA) y el certificado del firmante
     * @throws FirmaCms.CmsInvalidoException si no verifica, con el motivo; sin CA del simulador (la ferretería nunca firmó un
     *     certificado con él), {@code cms.cert.untrusted}: nada de lo que llegue lo firmó su CA
     */
    @Transactional(readOnly = true)
    public FirmaCms.Verificado verificar(String cmsBase64) {
        Objects.requireNonNull(cmsBase64, "el CMS");
        String ca = cas.deLaFerreteriaScoped().map(CaSimulador::getCertificado).orElseThrow(
                () -> new FirmaCms.CmsInvalidoException(FirmaCms.CmsInvalidoException.Motivo.OTRA_CA));
        return FirmaCms.verificar(cmsBase64, ca);
    }
}
