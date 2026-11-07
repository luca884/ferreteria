package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Propiedad de {@code yaEnElDestino} sobre planes al azar (semilla fija) de los seis modos: cuenta los articulos sin
 * error que ya estan en el rubro destino, solo en CAMBIAR_RUBRO; nunca pasa de {@code sinCambioDePrecio}; y el total
 * sigue cuadrando. Sale de la sonda del verificador de menores-mejoras-nuevas.
 */
class ConteosYaEnElDestinoPropiedadTest {

    private static final String[] VALORES_PCT = {"10", "-10", "33.33", "-99.99", "0", "1000", "-100", "5.5"};
    private static final String[] VALORES_MONTO = {"100", "-100", "25.50", "-170", "-1000000", "0.01"};
    private static final String[] VALORES_MARGEN = {"0", "30", "45", "80", "1000"};
    private static final Long[] RUBROS = {null, 1L, 2L, 3L};

    @Test
    @DisplayName("en 3000 planes al azar, yaEnElDestino es el conteo de los que ya estan en el destino (0 fuera de CAMBIAR_RUBRO)")
    void yaEnElDestinoCuentaLosQueYaEstanEnElDestino() {
        Random r = new Random(20261107L);
        int evaluados = 0;
        int conYaEnElDestino = 0;
        for (int s = 0; s < 3000; s++) {
            ContextosDePrecio contextos = contextos(r);
            List<ArticuloVigente> articulos = articulos(r);
            OperacionMasiva operacion = operacion(r);
            FiltroArticulos filtro = new FiltroArticulos(r.nextBoolean() ? 7L : null, null, null, false);
            PlanMasivo plan;
            try {
                plan = PlanificadorMasivo.planificar(articulos, filtro, operacion, contextos);
            } catch (RuntimeException e) {
                // Entradas fuera del dominio (las rechaza la validacion antes de llegar aca): no son parte de la propiedad.
                continue;
            }
            evaluados++;
            ConteosMasivos c = plan.conteos();
            assertThat(c.total()).as("plan %d", s)
                .isEqualTo(c.suben() + c.bajan() + c.sinCambioDePrecio() + c.conError());
            assertThat(c.yaEnElDestino()).as("plan %d", s)
                .isEqualTo(esperado(operacion, articulos, plan))
                .isBetween(0, c.sinCambioDePrecio());
            if (c.yaEnElDestino() > 0) {
                conYaEnElDestino++;
            }
        }
        assertThat(evaluados).isGreaterThan(2000);
        assertThat(conYaEnElDestino).isPositive();
    }

    private static int esperado(OperacionMasiva operacion, List<ArticuloVigente> articulos, PlanMasivo plan) {
        if (operacion.modo() != ModoMasivo.CAMBIAR_RUBRO) {
            return 0;
        }
        Set<Long> conError = new HashSet<>();
        plan.errores().forEach(e -> conError.add(e.articuloId()));
        return (int) articulos.stream()
            .filter(a -> operacion.rubroDestinoId().equals(a.rubroId()) && !conError.contains(a.id()))
            .count();
    }

    private static OperacionMasiva operacion(Random r) {
        ModoMasivo modo = ModoMasivo.values()[r.nextInt(ModoMasivo.values().length)];
        return switch (modo) {
            case CAMBIAR_RUBRO -> OperacionMasiva.cambiarRubro(1L + r.nextInt(3));
            case COSTO_PORCENTAJE, PRECIO_PORCENTAJE ->
                new OperacionMasiva(modo, new BigDecimal(VALORES_PCT[r.nextInt(VALORES_PCT.length)]));
            case COSTO_MONTO, PRECIO_MONTO ->
                new OperacionMasiva(modo, new BigDecimal(VALORES_MONTO[r.nextInt(VALORES_MONTO.length)]));
            default -> new OperacionMasiva(modo, new BigDecimal(VALORES_MARGEN[r.nextInt(VALORES_MARGEN.length)]));
        };
    }

    private static List<ArticuloVigente> articulos(Random r) {
        Map<Long, ArticuloVigente> porId = new HashMap<>();
        int n = r.nextInt(12);
        for (int i = 0; i < n; i++) {
            ArticuloVigente articulo = articulo(r, i + 1L + r.nextInt(3) * 100L);
            porId.put(articulo.id(), articulo);
        }
        return new ArrayList<>(porId.values());
    }

    private static ContextosDePrecio contextos(Random r) {
        BigDecimal defecto = new BigDecimal(new String[] {"30", "40", "0"}[r.nextInt(3)]);
        BigDecimal multiplo = new BigDecimal(new String[] {"1", "10", "0.01", "50"}[r.nextInt(4)]);
        BigDecimal usd = r.nextInt(4) == 0 ? null : new BigDecimal("1200.50");
        Map<Long, ContextoPrecio> porRubro = new HashMap<>();
        for (long id = 1; id <= 3; id++) {
            BigDecimal margenRubro = r.nextInt(3) == 0
                ? null
                : new BigDecimal(new String[] {"35", "45", "50", "0"}[r.nextInt(4)]);
            porRubro.put(id, new ContextoPrecio(margenRubro, defecto, multiplo, usd));
        }
        return new ContextosDePrecio(porRubro, new ContextoPrecio(null, defecto, multiplo, usd));
    }

    private static ArticuloVigente articulo(Random r, long id) {
        BigDecimal costo = new BigDecimal(
            new String[] {"100.00", "0.00", "1.00", "250.75", "99999.99", "12.34"}[r.nextInt(6)]);
        BigDecimal precio = new BigDecimal(new String[] {"170.00", "190.00", "200.00", "0.01", "500.00", "121.00",
            "9999999999999.99"}[r.nextInt(7)]);
        BigDecimal margen = r.nextInt(3) == 0 ? new BigDecimal(new String[] {"50", "30", "0"}[r.nextInt(3)]) : null;
        Long rubro = RUBROS[r.nextInt(RUBROS.length)];
        AlicuotaIva iva = AlicuotaIva.values()[r.nextInt(AlicuotaIva.values().length)];
        Moneda moneda = r.nextInt(4) == 0 ? Moneda.USD : Moneda.ARS;
        boolean manual = r.nextInt(4) == 0;
        AjustesDePrecio ajustes = r.nextInt(4) == 0 ? ajuste(r) : AjustesDePrecio.NINGUNO;
        RecargosDeCosto recargos = r.nextInt(5) == 0
            ? new RecargosDeCosto(List.of(new RecargoCosto("flete", new BigDecimal("5"))))
            : RecargosDeCosto.NINGUNO;
        return new ArticuloVigente(id, "INT-" + id, "P-" + id, "Articulo " + id, costo, precio, margen, rubro, iva,
            true, moneda, Ajustes.NINGUNO, manual, ajustes, recargos);
    }

    private static AjustesDePrecio ajuste(Random r) {
        boolean monto = r.nextBoolean();
        String[] valores = monto ? new String[] {"-50", "10", "-500"} : new String[] {"-50", "10", "-99.99"};
        return new AjustesDePrecio(List.of(new AjustePrecio("x",
            monto ? TipoAjustePrecio.MONTO : TipoAjustePrecio.PORCENTAJE, new BigDecimal(valores[r.nextInt(3)]))));
    }
}
