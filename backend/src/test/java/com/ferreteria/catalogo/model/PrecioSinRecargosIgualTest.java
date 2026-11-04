package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Regresion de CU-13: sin recargos, cada entrada del precio da lo mismo que la regla de antes (5 parametros). */
class PrecioSinRecargosIgualTest {

    private static final BigDecimal[] MULTIPLOS = {null, BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("10"),
        new BigDecimal("50"), new BigDecimal("0.05")};

    private static BigDecimal dec(Random r, int maxEntero) {
        return BigDecimal.valueOf(r.nextInt(maxEntero) * 100L + r.nextInt(100), 2);
    }

    @Test
    void miles_de_casos_sin_recargos_dan_el_precio_de_siempre() {
        Random r = new Random(13);
        AlicuotaIva[] alicuotas = AlicuotaIva.values();
        for (int n = 0; n < 20_000; n++) {
            BigDecimal costo = dec(r, 500_000);
            BigDecimal costoNuevo = dec(r, 500_000);
            BigDecimal margen = r.nextBoolean() ? null : dec(r, 300);
            BigDecimal margenDefecto = dec(r, 100);
            BigDecimal multiplo = MULTIPLOS[r.nextInt(MULTIPLOS.length)];
            AlicuotaIva iva = alicuotas[r.nextInt(alicuotas.length)];
            boolean usd = r.nextInt(4) == 0;
            BigDecimal dolar = usd ? dec(r, 2000) .add(BigDecimal.ONE) : null;
            ContextoPrecio ctx = new ContextoPrecio(null, margenDefecto, multiplo, dolar);
            Moneda moneda = usd ? Moneda.USD : Moneda.ARS;
            BigDecimal cot = usd ? dolar : BigDecimal.ONE;
            BigDecimal efectivo = PrecioCalculator.margenEfectivo(margen, null, margenDefecto);

            BigDecimal esperado = PrecioCalculator.precioFinal(costoNuevo, efectivo, iva, multiplo, cot);

            // Alta / edicion.
            Articulo a = new Articulo(1L);
            a.actualizar(new DatosArticulo("C" + n, null, null, "d", null, null, costo, margen, iva, Unidad.UNIDAD,
                BigDecimal.ZERO, null, true, moneda), ctx);
            assertThat(a.getRecargosCosto().estaVacia()).isTrue();
            assertThat(a.precioConCosto(costoNuevo, ctx)).as("precioConCosto " + n).isEqualTo(esperado);
            assertThat(a.precioConCostoAutomatico(costoNuevo, ctx).precio()).isEqualTo(esperado);
            assertThat(a.precioConCostoAutomatico(costoNuevo, RecargosDeCosto.NINGUNO, ctx).precio()).isEqualTo(esperado);
            // Compra: costo y recargos vacios en un paso = ajustarCostoAutomatico de antes.
            Articulo b = new Articulo(1L);
            b.actualizar(new DatosArticulo("C" + n, null, null, "d", null, null, costo, margen, iva, Unidad.UNIDAD,
                BigDecimal.ZERO, null, true, moneda), ctx);
            b.ajustarCostoYRecargosAutomatico(costoNuevo, new RecargosDeCosto(new ArrayList<>()), ctx);
            assertThat(b.getPrecioFinal()).as("compra " + n).isEqualTo(esperado);
            assertThat(b.costoReal()).isSameAs(b.getCosto());
            // CU-05 / CU-04: la foto del articulo.
            ArticuloVigente v = ArticuloVigente.de(a);
            assertThat(v.precioCon(costoNuevo, efectivo, iva, multiplo, cot)).isEqualTo(esperado);
            assertThat(v.costoReal(costoNuevo)).isSameAs(costoNuevo);
            // Margen negativo.
            BigDecimal precio = dec(r, 500_000);
            assertThat(MargenNegativo.mensaje(costoNuevo, RecargosDeCosto.NINGUNO, cot, iva, precio))
                .isEqualTo(MargenNegativo.mensaje(costoNuevo, cot, iva, precio));
        }
    }

    @Test
    void la_lista_vacia_de_la_base_devuelve_el_mismo_costo() {
        BigDecimal costo = new BigDecimal("123.4567");
        assertThat(new RecargosDeCosto(List.of()).aplicar(costo)).isSameAs(costo);
        assertThat(new RecargosDeCosto(null).aplicar(costo)).isSameAs(costo);
    }
}
