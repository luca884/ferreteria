package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Oferta del articulo (ofertas, S1): una capa aparte del precio normal. Dominio puro. */
class ArticuloOfertaTest {

    /** Sin multiplo: el precio queda al centavo. Costo 100, margen 35, IVA 21 da 163,35. */
    private static final ContextoPrecio CTX = new ContextoPrecio(null, bd("30"), BigDecimal.ZERO);
    private static final LocalDate HOY = LocalDate.of(2026, 11, 4);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static DatosArticulo datos(String costo) {
        return new DatosArticulo("TORN-001", null, null, "Tornillo", null, null, bd(costo), bd("35"),
                AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true);
    }

    private static Articulo articulo() {
        Articulo articulo = new Articulo(1L);
        articulo.actualizar(datos("100"), CTX);
        return articulo;
    }

    private static Oferta oferta(TipoOferta tipo, String valor, LocalDate hasta) {
        return new Oferta(tipo, bd(valor), hasta);
    }

    private static String errorEn(Runnable accion, String campo) {
        try {
            accion.run();
        } catch (DatosInvalidosException e) {
            assertThat(e.errors()).containsOnlyKeys(campo);
            return e.errors().get(campo);
        }
        throw new AssertionError("se esperaba un error en " + campo);
    }

    @Test
    @DisplayName("sin oferta el precio vigente es el normal y no esta en oferta")
    void sinOferta() {
        Articulo articulo = articulo();

        assertThat(articulo.getOferta()).isEmpty();
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("163.35"));
        assertThat(articulo.enOfertaEl(HOY)).isFalse();
    }

    @Test
    @DisplayName("con precio fijo el precio vigente es el de la oferta y el normal no cambia")
    void precioFijo() {
        Articulo articulo = articulo();

        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", null), HOY);

        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("150.00"));
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("163.35"));
        assertThat(articulo.enOfertaEl(HOY)).isTrue();
        assertThat(articulo.getOferta()).contains(oferta(TipoOferta.PRECIO_FIJO, "150.00", null));
    }

    @Test
    @DisplayName("el descuento en % se redondea al centavo hacia arriba, como los ajustes del precio")
    void descuentoPorcentaje() {
        Articulo articulo = articulo();

        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_PORCENTAJE, "10", null), HOY);

        // 163,35 x 0,90 = 147,015 -> 147,02
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("147.02"));
    }

    @Test
    @DisplayName("el descuento en $ se resta del precio normal")
    void descuentoMonto() {
        Articulo articulo = articulo();

        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_MONTO, "13.35", null), HOY);

        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("150.00"));
    }

    @Test
    @DisplayName("la oferta vale hasta el dia de fin inclusive y despues vuelve el precio normal")
    void venceSola() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", HOY.plusDays(2)), HOY);

        assertThat(articulo.precioVigenteEl(HOY.plusDays(2))).isEqualTo(bd("150.00"));
        assertThat(articulo.enOfertaEl(HOY.plusDays(2))).isTrue();
        assertThat(articulo.precioVigenteEl(HOY.plusDays(3))).isEqualTo(bd("163.35"));
        assertThat(articulo.enOfertaEl(HOY.plusDays(3))).isFalse();
        assertThat(articulo.getOferta()).isPresent();
    }

    @Test
    @DisplayName("el descuento en % sigue al precio normal cuando se recalcula")
    void porcentajeSigueAlPrecioNormal() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_PORCENTAJE, "50", null), HOY);

        articulo.ajustarCosto(bd("200"), CTX);

        // normal 326,70 -> mitad 163,35
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("326.70"));
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("163.35"));
    }

    @Test
    @DisplayName("si el precio normal baja por debajo de la oferta, se vende al normal y no se marca la oferta")
    void normalPorDebajoDeLaOferta() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", null), HOY);

        articulo.ajustarCosto(bd("50"), CTX);

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("81.68"));
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("81.68"));
        assertThat(articulo.enOfertaEl(HOY)).isFalse();
    }

    @Test
    @DisplayName("un descuento en $ que ya no entra en el precio normal no aplica")
    void montoMayorAlNormalNoAplica() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_MONTO, "100", null), HOY);

        articulo.ajustarCosto(bd("50"), CTX);

        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("81.68"));
        assertThat(articulo.enOfertaEl(HOY)).isFalse();
    }

    @Test
    @DisplayName("quitar la oferta vuelve al precio normal")
    void quitar() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", null), HOY);

        articulo.quitarOferta();

        assertThat(articulo.getOferta()).isEmpty();
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("163.35"));
    }

    @Test
    @DisplayName("una oferta nueva reemplaza a la anterior")
    void reemplaza() {
        Articulo articulo = articulo();
        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", null), HOY);

        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_MONTO, "3.35", HOY), HOY);

        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("160.00"));
        assertThat(articulo.getOferta()).contains(oferta(TipoOferta.DESCUENTO_MONTO, "3.35", HOY));
    }

    @Test
    @DisplayName("el precio de oferta tiene que quedar por debajo del precio normal")
    void tieneQueSerMenorAlNormal() {
        Articulo articulo = articulo();
        String esperado = "El precio de oferta tiene que ser menor al precio normal.";

        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "163.35", null), HOY), "valor"))
                .isEqualTo(esperado);
        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_MONTO, "163.35", null), HOY),
                "valor")).isEqualTo(esperado);
        assertThat(articulo.getOferta()).isEmpty();
    }

    @Test
    @DisplayName("el descuento en % va de 0,01 a 99,99")
    void rangoDelPorcentaje() {
        Articulo articulo = articulo();

        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_PORCENTAJE, "100", null), HOY),
                "valor")).isEqualTo("El descuento va de 0,01 % a 99,99 %.");
        articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_PORCENTAJE, "99.99", null), HOY);
        assertThat(articulo.precioVigenteEl(HOY)).isEqualTo(bd("0.02"));
    }

    @Test
    @DisplayName("el valor tiene que ser mayor a 0 y con hasta 2 decimales")
    void valorValido() {
        Articulo articulo = articulo();

        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "0", null), HOY), "valor"))
                .isEqualTo("El valor tiene que ser mayor a 0.");
        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.DESCUENTO_MONTO, "-1", null), HOY), "valor"))
                .isEqualTo("El valor tiene que ser mayor a 0.");
        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150.001", null), HOY), "valor"))
                .isEqualTo("El valor admite hasta 2 decimales.");
    }

    @Test
    @DisplayName("la fecha de fin no puede ser anterior a hoy; hoy si")
    void fechaDeFin() {
        Articulo articulo = articulo();

        assertThat(errorEn(() -> articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", HOY.minusDays(1)), HOY),
                "hasta")).isEqualTo("La fecha de fin no puede ser anterior a hoy.");
        articulo.fijarOferta(oferta(TipoOferta.PRECIO_FIJO, "150", HOY), HOY);
        assertThat(articulo.enOfertaEl(HOY)).isTrue();
    }

    @Test
    @DisplayName("la oferta necesita tipo y valor")
    void guardas() {
        assertThatThrownBy(() -> new Oferta(null, bd("1"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Oferta(TipoOferta.PRECIO_FIJO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
