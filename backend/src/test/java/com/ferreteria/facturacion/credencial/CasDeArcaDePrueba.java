package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.credencial.CasDeArca.CaDeArca;
import com.ferreteria.facturacion.model.Ambiente;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Las CA de ARCA de los tests ({@code @Import(CasDeArcaDePrueba.class)}): una de homologación y otra de producción,
 * generadas en el momento, en lugar de las oficiales (que siguen cargando en el contexto, pero no se inyectan). Los nombres
 * son los de las oficiales para que el emisor se vea igual; el ambiente lo decide la firma.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CasDeArcaDePrueba {

    public static final AutoridadDePrueba HOMOLOGACION = AutoridadDePrueba.nueva("Computadores Test");
    public static final AutoridadDePrueba PRODUCCION = AutoridadDePrueba.nueva("Computadores");
    /** Una CA que no es de ARCA. */
    public static final AutoridadDePrueba AJENA = AutoridadDePrueba.nueva("Otra CA");

    @Bean
    @Primary
    public CasDeArca casDeArcaDePrueba() {
        return new CasDeArca(List.of(new CaDeArca(Ambiente.HOMOLOGACION, HOMOLOGACION.certificado()),
                new CaDeArca(Ambiente.PRODUCCION, PRODUCCION.certificado())));
    }
}
