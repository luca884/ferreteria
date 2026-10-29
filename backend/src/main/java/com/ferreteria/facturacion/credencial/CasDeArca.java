package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.model.Ambiente;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Las CA de ARCA que firman los certificados de los web services (C3): {@code Computadores} en producción y
 * {@code Computadores Test} en homologación. Un certificado subido es de producción o de homologación según cuál de las dos
 * verifica su firma (S4), nunca por el texto del emisor. Una por ambiente; el simulador no está acá (su CA es de cada
 * ferretería, {@link CaSimulador}).
 *
 * <p>Es un bean ({@link CasDeArcaConfig}): la aplicación carga los PEM oficiales, y los tests lo reemplazan por CA generadas
 * en el momento, porque ARCA no firma certificados de prueba.
 */
public final class CasDeArca {

    /**
     * Una CA de ARCA.
     *
     * @param ambiente el ambiente de los certificados que firma: HOMOLOGACION o PRODUCCION
     * @param nombre su CN, el emisor que ve el Dueño («Computadores Test»)
     */
    public record CaDeArca(Ambiente ambiente, String nombre, X509Certificate certificado) {

        public CaDeArca(Ambiente ambiente, X509Certificate certificado) {
            this(ambiente, Certificados.nombreComun(certificado), certificado);
        }
    }

    private final Map<Ambiente, CaDeArca> porAmbiente = new EnumMap<>(Ambiente.class);

    public CasDeArca(List<CaDeArca> cas) {
        for (CaDeArca ca : cas) {
            if (ca.ambiente() == Ambiente.SIMULADOR) {
                throw new IllegalArgumentException("La CA del simulador no es una CA de ARCA.");
            }
            if (porAmbiente.put(ca.ambiente(), ca) != null) {
                throw new IllegalArgumentException("Hay dos CA de ARCA para " + ca.ambiente() + ".");
            }
        }
        if (porAmbiente.size() != 2) {
            throw new IllegalArgumentException("Faltan las CA de ARCA de homologación o de producción.");
        }
    }

    public List<CaDeArca> todas() {
        return List.copyOf(porAmbiente.values());
    }

    public CaDeArca de(Ambiente ambiente) {
        return Optional.ofNullable(porAmbiente.get(ambiente))
                .orElseThrow(() -> new IllegalArgumentException("No hay una CA de ARCA para " + ambiente + "."));
    }

    /** La CA cuya clave pública verifica la firma del certificado; vacío si no lo firmó ninguna de ARCA. */
    public Optional<CaDeArca> laQueFirmo(X509Certificate certificado) {
        return porAmbiente.values().stream().filter(ca -> verifica(certificado, ca)).findFirst();
    }

    private static boolean verifica(X509Certificate certificado, CaDeArca ca) {
        try {
            certificado.verify(ca.certificado().getPublicKey());
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
