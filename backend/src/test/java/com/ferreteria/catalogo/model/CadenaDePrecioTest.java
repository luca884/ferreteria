package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dominio puro: los pasos del desglose de un precio. Ferreteria con margen por defecto 30 y multiplo 10 salvo que el
 * test diga otra cosa; los importes intermedios van a 4 decimales y el costo y el precio final a 2.
 */
class CadenaDePrecioTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** Entrada de prueba: lista 1000,00 sin IVA, bonificacion 10, ajustes -5 y +3, margen propio 30, IVA 21, multiplo 10. */
    private static final class Entrada {
        private boolean tieneLista = true;
        private String lista = "1000.00";
        private boolean incluyeIva = false;
        private AlicuotaIva iva = AlicuotaIva.IVA_21;
        private Bonificaciones bonificaciones = new Bonificaciones(bd("10"), null, null);
        private Ajustes ajustes = new Ajustes(bd("-5"), bd("3"), null);
        private String costoGuardado = "880.65";
        private Moneda moneda = Moneda.ARS;
        private String cotizacion;
        private boolean congelada = false;
        private String margenPropio = "30";
        private String margenRubro;
        private String margenDefecto = "30";
        private String precioGuardado = "1390.00";

        Entrada lista(String valor) {
            this.lista = valor;
            return this;
        }

        Entrada conIva() {
            this.incluyeIva = true;
            return this;
        }

        Entrada bonificaciones(String b1) {
            this.bonificaciones = new Bonificaciones(b1 == null ? null : bd(b1), null, null);
            return this;
        }

        Entrada ajustes(String a1, String a2, String a3) {
            this.ajustes = new Ajustes(a1 == null ? null : bd(a1), a2 == null ? null : bd(a2), a3 == null ? null : bd(a3));
            return this;
        }

        Entrada costoGuardado(String valor) {
            this.costoGuardado = valor;
            return this;
        }

        Entrada precioGuardado(String valor) {
            this.precioGuardado = valor;
            return this;
        }

        Entrada dolares(String cotizacion) {
            this.moneda = Moneda.USD;
            this.cotizacion = cotizacion;
            return this;
        }

        Entrada congelada() {
            this.congelada = true;
            return this;
        }

        Entrada margen(String propio, String rubro, String defecto) {
            this.margenPropio = propio;
            this.margenRubro = rubro;
            this.margenDefecto = defecto;
            return this;
        }

        Entrada sinLista() {
            this.tieneLista = false;
            return this;
        }

        CadenaDePrecio cadena() {
            return CadenaDePrecio.de(new EntradaCadena(tieneLista, bd(lista), incluyeIva, iva, bonificaciones, ajustes,
                bd(costoGuardado), moneda, cotizacion == null ? null : bd(cotizacion), congelada,
                margenPropio == null ? null : bd(margenPropio), margenRubro == null ? null : bd(margenRubro),
                margenDefecto == null ? null : bd(margenDefecto), bd("10"), bd(precioGuardado)));
        }
    }

    private static List<TipoPaso> tipos(CadenaDePrecio cadena) {
        return cadena.pasos().stream().map(PasoDePrecio::tipo).toList();
    }

    private static List<String> importes(CadenaDePrecio cadena) {
        return cadena.pasos().stream().map(p -> p.importe().toPlainString()).toList();
    }

    @Test
    @DisplayName("lista 1000 sin IVA, bonificacion 10, ajustes -5 y +3, margen 30, IVA 21, multiplo 10: la cadena completa")
    void cadenaCompleta() {
        CadenaDePrecio cadena = new Entrada().cadena();

        assertThat(tipos(cadena)).containsExactly(TipoPaso.LISTA, TipoPaso.BONIFICACION, TipoPaso.AJUSTE, TipoPaso.AJUSTE,
            TipoPaso.COSTO, TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(importes(cadena)).containsExactly("1000.0000", "900.0000", "855.0000", "880.6500", "880.65",
            "1144.8450", "1385.2625", "10", "1390.00");
        assertThat(cadena.pasos()).extracting(PasoDePrecio::porcentaje)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(null, bd("10"), bd("-5"), bd("3"), null, bd("30"), bd("21"), null, null);
        assertThat(cadena.costoDeLista()).isEqualTo(bd("880.65"));
        assertThat(cadena.coincide()).isTrue();
        assertThat(cadena.cotizacion()).isNull();
    }

    @Test
    @DisplayName("lista 1210 con IVA 21 incluido y +10: paso SIN_IVA 1000, ajuste 1100, costo 1100 y precio guardado 1740")
    void listaConIvaIncluido() {
        CadenaDePrecio cadena = new Entrada().lista("1210.00").conIva().bonificaciones(null).ajustes("10", null, null)
            .costoGuardado("1100.00").precioGuardado("1740.00").cadena();

        assertThat(tipos(cadena)).containsExactly(TipoPaso.LISTA, TipoPaso.SIN_IVA, TipoPaso.AJUSTE, TipoPaso.COSTO,
            TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(importes(cadena).subList(0, 4)).containsExactly("1210.0000", "1000.0000", "1100.0000", "1100.00");
        assertThat(cadena.pasos().get(1).porcentaje()).isEqualByComparingTo("21");
        assertThat(cadena.pasos().get(7).importe()).isEqualTo(bd("1740.00"));
    }

    @Test
    @DisplayName("333,33 con -10: el paso del ajuste muestra 299,9970, el costo 300,00 y el precio 480")
    void unSoloRedondeoAlFinal() {
        CadenaDePrecio cadena = new Entrada().lista("333.33").bonificaciones(null).ajustes("-10", null, null)
            .costoGuardado("300.00").precioGuardado("480.00").cadena();

        assertThat(importes(cadena)).containsExactly("333.3300", "299.9970", "300.00", "390.0000", "471.9000", "10", "480.00");
    }

    @Test
    @DisplayName("USD con lista 10, bonificacion 10, ajustes -5 y +3, dolar 1000: costo 8,81, en pesos 8810, margen 11453 e IVA 13858,13")
    void enDolares() {
        CadenaDePrecio cadena = new Entrada().lista("10.00").dolares("1000.00").costoGuardado("8.81")
            .precioGuardado("13860.00").cadena();

        assertThat(tipos(cadena)).containsExactly(TipoPaso.LISTA, TipoPaso.BONIFICACION, TipoPaso.AJUSTE, TipoPaso.AJUSTE,
            TipoPaso.COSTO, TipoPaso.COTIZACION, TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(importes(cadena)).containsExactly("10.0000", "9.0000", "8.5500", "8.8065", "8.81", "8810.0000",
            "11453.0000", "13858.1300", "10", "13860.00");
        assertThat(cadena.cotizacion()).isEqualByComparingTo("1000");
        assertThat(cadena.costoDeLista()).isEqualTo(bd("8.81"));
    }

    @Test
    @DisplayName("USD sin ninguna cotizacion: cotizacion null y ningun paso despues del costo")
    void dolaresSinCotizacion() {
        CadenaDePrecio cadena = new Entrada().lista("10.00").dolares(null).costoGuardado("8.81").cadena();

        assertThat(cadena.cotizacion()).isNull();
        assertThat(tipos(cadena)).endsWith(TipoPaso.COSTO).doesNotContain(TipoPaso.COTIZACION, TipoPaso.MARGEN,
            TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
    }

    @Test
    @DisplayName("sin lista: ningun paso de lista, arranca en el costo guardado y coincide es null")
    void sinLista() {
        CadenaDePrecio cadena = new Entrada().sinLista().costoGuardado("500.00").precioGuardado("790.00").cadena();

        assertThat(tipos(cadena)).containsExactly(TipoPaso.COSTO, TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(importes(cadena)).containsExactly("500.00", "650.0000", "786.5000", "10", "790.00");
        assertThat(cadena.costoDeLista()).isNull();
        assertThat(cadena.coincide()).isNull();
    }

    @Test
    @DisplayName("margen del rubro 35: origen RUBRO; margen propio 0: 0 % y origen PROPIO; ARS sin paso de cotizacion")
    void origenDelMargen() {
        CadenaDePrecio delRubro = new Entrada().margen(null, "35", "30").cadena();
        assertThat(delRubro.margen()).isEqualByComparingTo("35");
        assertThat(delRubro.origenMargen()).isEqualTo(OrigenMargen.RUBRO);

        CadenaDePrecio propioCero = new Entrada().margen("0", "35", "30").cadena();
        assertThat(propioCero.margen()).isEqualByComparingTo("0");
        assertThat(propioCero.origenMargen()).isEqualTo(OrigenMargen.PROPIO);
        assertThat(propioCero.pasos()).filteredOn(p -> p.tipo() == TipoPaso.MARGEN)
            .extracting(PasoDePrecio::porcentaje).singleElement().satisfies(p -> assertThat(p).isEqualByComparingTo("0"));

        CadenaDePrecio delDefecto = new Entrada().margen(null, null, "30").cadena();
        assertThat(delDefecto.origenMargen()).isEqualTo(OrigenMargen.FERRETERIA);
        assertThat(tipos(delDefecto)).doesNotContain(TipoPaso.COTIZACION);
    }

    @Test
    @DisplayName("bonificacion 10 y costo guardado 900: coincide; guardado 990 o 950: no coincide y trae las dos cifras")
    void coincideConElCostoDeLaLista() {
        Entrada base = new Entrada().ajustes(null, null, null);

        CadenaDePrecio coincide = base.costoGuardado("900.00").cadena();
        assertThat(coincide.costoDeLista()).isEqualTo(bd("900.00"));
        assertThat(coincide.coincide()).isTrue();

        CadenaDePrecio masivo = base.costoGuardado("990.00").cadena();
        assertThat(masivo.costoDeLista()).isEqualTo(bd("900.00"));
        assertThat(masivo.coincide()).isFalse();

        assertThat(base.costoGuardado("950.00").cadena().coincide()).isFalse();
    }

    @Test
    @DisplayName("coincide compara por valor y no por escala: 900 y 900.0 coinciden")
    void coincidePorValor() {
        assertThat(new Entrada().ajustes(null, null, null).costoGuardado("900.0").cadena().coincide()).isTrue();
    }

    @Test
    @DisplayName("la bonificacion que trae la entrada es la guardada: con 10 coincide aunque el proveedor tenga 12")
    void usaLaBonificacionDeLaEntrada() {
        CadenaDePrecio cadena = new Entrada().ajustes(null, null, null).costoGuardado("900.00").cadena();

        assertThat(cadena.pasos().get(1).porcentaje()).isEqualByComparingTo("10");
        assertThat(cadena.coincide()).isTrue();
    }

    @Test
    @DisplayName("el PRECIO_FINAL es siempre el precio guardado, aunque el calculo daria otro")
    void precioFinalEsElGuardado() {
        CadenaDePrecio cadena = new Entrada().dolares("1100.00").lista("10.00").costoGuardado("8.81")
            .precioGuardado("13860.00").cadena();

        assertThat(cadena.pasos().get(cadena.pasos().size() - 1).tipo()).isEqualTo(TipoPaso.PRECIO_FINAL);
        assertThat(cadena.pasos().get(cadena.pasos().size() - 1).importe()).isEqualTo(bd("13860.00"));
    }

    @Test
    @DisplayName("el costo sale de CostoDeLista: ajuste +3,5 sobre 200 da 207,00 y -99,99 sobre 1000 da 0,10")
    void costoDeListaDelDominio() {
        assertThat(new Entrada().lista("200.00").bonificaciones(null).ajustes("3.5", null, null).costoGuardado("207.00")
            .cadena().costoDeLista()).isEqualTo(bd("207.00"));
        assertThat(new Entrada().bonificaciones(null).ajustes("-99.99", null, null).costoGuardado("0.10")
            .cadena().costoDeLista()).isEqualTo(bd("0.10"));
    }

    @Test
    @DisplayName("una bonificacion o ajuste en cero se muestra (no es nula) y no cambia el importe")
    void cerosSeMuestran() {
        CadenaDePrecio cadena = new Entrada().bonificaciones("0").ajustes("0", null, null).costoGuardado("1000.00").cadena();

        assertThat(tipos(cadena)).startsWith(TipoPaso.LISTA, TipoPaso.BONIFICACION, TipoPaso.AJUSTE, TipoPaso.COSTO);
        assertThat(importes(cadena).subList(0, 4)).containsExactly("1000.0000", "1000.0000", "1000.0000", "1000.00");
    }

    @Test
    @DisplayName("el paso REDONDEO lleva el multiplo (10), va entre el IVA y el precio final y no tiene porcentaje")
    void pasoDeRedondeo() {
        CadenaDePrecio cadena = new Entrada().cadena();

        PasoDePrecio redondeo = cadena.pasos().get(cadena.pasos().size() - 2);
        assertThat(redondeo.tipo()).isEqualTo(TipoPaso.REDONDEO);
        assertThat(redondeo.importe()).isEqualByComparingTo("10");
        assertThat(redondeo.porcentaje()).isNull();
        assertThat(tipos(cadena).get(cadena.pasos().size() - 3)).isEqualTo(TipoPaso.IVA);
    }

    @Test
    @DisplayName("la cotizacion congelada (cotizacion apagada) de un articulo en dolares se marca en la cadena")
    void cotizacionCongelada() {
        CadenaDePrecio usd = new Entrada().lista("10.00").dolares("1000.00").costoGuardado("8.81").congelada().cadena();
        assertThat(usd.cotizacionCongelada()).isTrue();
        assertThat(usd.cotizacion()).isEqualByComparingTo("1000");

        CadenaDePrecio vigente = new Entrada().lista("10.00").dolares("1000.00").costoGuardado("8.81").cadena();
        assertThat(vigente.cotizacionCongelada()).isFalse();
    }

    @Test
    @DisplayName("un articulo en pesos nunca marca cotizacion congelada, aunque la entrada lo diga")
    void enPesosNoMarcaCongelada() {
        assertThat(new Entrada().congelada().cadena().cotizacionCongelada()).isFalse();
    }
}
