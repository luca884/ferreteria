package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Sonda del odd-verifier de para-decidir-caja-y-margen (S4), como regresion: con casos al azar cerca del limite
 * (semilla fija), el aviso de margen negativo sale si y solo si el costo real en pesos redondeado supera el precio sin
 * IVA redondeado (los dos a 2 decimales HALF_UP), y nunca avisa donde la cuenta exacta no avisaria.
 */
class MargenNegativoPropiedadTest {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final int CASOS = 20_000;

    @Test
    void avisaSiYSoloSiLosImportesRedondeadosLoDicen() {
        Random r = new Random(4242);
        int avisos = 0;
        int dejanDeAvisar = 0;
        for (int i = 0; i < CASOS; i++) {
            BigDecimal costo = r.nextInt(20) == 0 ? new BigDecimal("0.00")
                : BigDecimal.valueOf(r.nextInt(r.nextBoolean() ? 1_000 : 10_000_000)).movePointLeft(2);
            List<RecargoCosto> recargos = recargosAlAzar(r);
            BigDecimal cotizacion = r.nextInt(3) == 0
                ? BigDecimal.valueOf(1 + r.nextInt(20_000_000)).movePointLeft(r.nextInt(5)) : BigDecimal.ONE;
            AlicuotaIva iva = AlicuotaIva.values()[r.nextInt(AlicuotaIva.values().length)];
            BigDecimal real = new RecargosDeCosto(recargos).aplicar(costo);
            BigDecimal precio = precioCercaDelLimite(r, real.multiply(cotizacion), iva);

            Optional<String> aviso = MargenNegativo.mensaje(costo, new RecargosDeCosto(recargos), cotizacion, iva, precio);

            BigDecimal costoLeido = real.multiply(cotizacion).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sinIvaLeido = precio.multiply(CIEN).divide(CIEN.add(iva.porcentaje()), 2, RoundingMode.HALF_UP);
            boolean exacta = real.multiply(cotizacion).multiply(CIEN.add(iva.porcentaje()))
                .compareTo(precio.multiply(CIEN)) > 0;
            String caso = "costo " + costo + ", recargos " + recargos + ", cotizacion " + cotizacion + ", " + iva
                + ", precio " + precio;
            assertThat(aviso.isPresent()).as(caso).isEqualTo(costoLeido.compareTo(sinIvaLeido) > 0);
            if (aviso.isPresent()) {
                assertThat(exacta).as(caso).isTrue();
                avisos++;
            } else if (exacta) {
                assertThat(costoLeido).as(caso).isEqualByComparingTo(sinIvaLeido);
                dejanDeAvisar++;
            }
        }
        // El generador tiene que pasar por los dos lados del limite y por el caso de los importes iguales.
        assertThat(avisos).isGreaterThan(CASOS / 4);
        assertThat(dejanDeAvisar).isPositive();
    }

    private static List<RecargoCosto> recargosAlAzar(Random r) {
        List<RecargoCosto> recargos = new ArrayList<>();
        if (r.nextInt(4) == 0) {
            return recargos;
        }
        BigDecimal suma = BigDecimal.ZERO;
        int cuantos = r.nextInt(RecargosDeCosto.MAXIMO + 1);
        for (int k = 0; k < cuantos; k++) {
            BigDecimal p;
            do {
                p = BigDecimal.valueOf(r.nextInt(19_999) - 9_999).movePointLeft(2);
            } while (p.signum() == 0);
            if (CIEN.add(suma).add(p).signum() <= 0) {
                continue;
            }
            suma = suma.add(p);
            recargos.add(new RecargoCosto("R" + k, p));
        }
        return recargos;
    }

    /** Un cuarto al azar; el resto a unos centavos del precio que cubre justo el costo con IVA, con 0 a 2 decimales. */
    private static BigDecimal precioCercaDelLimite(Random r, BigDecimal costoEnPesos, AlicuotaIva iva) {
        BigDecimal precio;
        if (r.nextInt(4) == 0) {
            precio = BigDecimal.valueOf(1 + r.nextInt(10_000_000)).movePointLeft(2);
        } else {
            BigDecimal limite = costoEnPesos.multiply(CIEN.add(iva.porcentaje())).divide(CIEN, 10, RoundingMode.HALF_UP);
            precio = limite.add(BigDecimal.valueOf(r.nextInt(7) - 3).movePointLeft(2))
                .setScale(2, r.nextBoolean() ? RoundingMode.UP : RoundingMode.DOWN);
        }
        int decimales = r.nextInt(3);
        precio = precio.setScale(decimales, decimales == 2 ? RoundingMode.HALF_UP : RoundingMode.DOWN);
        return precio.signum() <= 0 ? BigDecimal.ONE : precio;
    }
}
