package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.credencial.CasDeArca.CaDeArca;
import com.ferreteria.facturacion.model.Ambiente;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.cert.CertificateException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Las CA oficiales de ARCA (C3), de los PEM de {@code facturacion/ca} (bajados de ARCA y verificados con
 * {@code openssl verify} contra su raíz el 2026-10-28: {@code AFIP Root CA 2} en producción y {@code AC Raiz Test} en
 * homologación; fines de línea LF). Van con extensión {@code .crt}, como los nombra ARCA: el {@code .gitignore} del repo
 * excluye todo {@code *.pem} por si es un secreto, y estos son públicos. Si uno falta o no se lee, la aplicación no arranca.
 */
@Configuration(proxyBeanMethods = false)
public class CasDeArcaConfig {

    static final String PRODUCCION = "facturacion/ca/Computadores.cacert_2024-2035.crt";
    static final String HOMOLOGACION = "facturacion/ca/ComputadoresTest.cacert_2022-2030.crt";

    @Bean
    public CasDeArca casDeArca() {
        return new CasDeArca(List.of(new CaDeArca(Ambiente.PRODUCCION, leer(PRODUCCION)),
                new CaDeArca(Ambiente.HOMOLOGACION, leer(HOMOLOGACION))));
    }

    private static java.security.cert.X509Certificate leer(String ruta) {
        try (InputStream pem = new ClassPathResource(ruta).getInputStream()) {
            return Certificados.leer(pem);
        } catch (IOException e) {
            throw new UncheckedIOException("No se encontró la CA de ARCA " + ruta + ".", e);
        } catch (CertificateException e) {
            throw new IllegalStateException("No se pudo leer la CA de ARCA " + ruta + ".", e);
        }
    }
}
