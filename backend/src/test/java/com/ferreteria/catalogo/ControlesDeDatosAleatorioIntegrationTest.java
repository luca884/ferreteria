package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sonda del odd-verifier de para-decidir-caja-y-margen (S5), como regresion: un catalogo al azar (semilla fija) en dos
 * ferreterias, con pesos y dolares, las seis alicuotas, recargos positivos y negativos, precio manual o no, inactivos y
 * precios cerca del limite. El control «Margen negativo» tiene que dar lo mismo que la regla del aviso calculada aca
 * aparte (costo real en pesos y precio sin IVA, redondeados), con y sin dolar; cada fila de los cuatro controles trae su
 * costo real; y ninguna ferreteria ve lo de la otra. CUIT 30-79092001-6 y 30-79092002-4.
 */
class ControlesDeDatosAleatorioIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos/controles?control=";
    private static final String[] ALICUOTAS = {"IVA_0", "IVA_2_5", "IVA_5", "IVA_10_5", "IVA_21", "IVA_27"};
    private static final String[] PORCENTAJES = {"0", "2.5", "5", "10.5", "21", "27"};
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal DOLAR = new BigDecimal("1234.5678");

    /** Lo que se cargo de cada articulo, para la cuenta independiente. */
    private record Cargado(long id, String codigo, String descripcion, BigDecimal costo, boolean usd, BigDecimal precio,
                           int alicuota, boolean activo, BigDecimal costoReal) {
    }

    private Long a;
    private Long b;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-79092001-6", "Controles al azar A");
        b = ferreteria("30-79092002-4", "Controles al azar B");
        borrarDatosDe(a, b);
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    @Test
    void elControlEsLaReglaDelAvisoConElCostoRealYCadaFerreteriaVeLoSuyo() throws Exception {
        String duenoA = login(usuario(a, "dueno-azar-a@test.local", "DUENO"));
        String duenoB = login(usuario(b, "dueno-azar-b@test.local", "DUENO"));
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id, vigente) values (?, ?)", a, DOLAR);
        Random r = new Random(4242);
        List<Cargado> deA = cargar(a, "QA-", 300, r);
        List<Cargado> deB = cargar(b, "QB-", 60, r);
        // El catalogo tiene que pasar por el caso de esta feature: articulos que caen solo por sus recargos.
        List<String> conDolar = esperados(deA, DOLAR);
        assertThat(conDolar).hasSizeGreaterThan(30);
        assertThat(deA).anyMatch(c -> conDolar.contains(c.codigo()) && c.costoReal() != null
                && c.costoReal().compareTo(c.costo()) > 0);

        assertThat(codigos(URL + "MARGEN_NEGATIVO", duenoA)).isEqualTo(conDolar);
        assertThat(codigos(URL + "MARGEN_NEGATIVO", duenoB)).isEqualTo(esperados(deB, null));
        for (String control : List.of("COSTO_CERO", "SIN_RUBRO", "MARGEN_NEGATIVO", "IVA_CERO")) {
            costoRealDeCadaFila(getJson(URL + control, duenoA), deA, deB);
            costoRealDeCadaFila(getJson(URL + control, duenoB), deB, deA);
        }

        borrarConfiguracion(a);

        assertThat(codigos(URL + "MARGEN_NEGATIVO", duenoA)).isEqualTo(esperados(deA, null));
    }

    private List<Cargado> cargar(Long ferreteriaId, String prefijo, int cuantos, Random r) {
        Long rubro = rubro(ferreteriaId, "Rubro " + prefijo, "30");
        List<Cargado> cargados = new ArrayList<>();
        for (int i = 0; i < cuantos; i++) {
            String codigo = prefijo + String.format("%04d", i);
            String descripcion = "" + (char) ('A' + r.nextInt(6)) + (char) ('A' + r.nextInt(6)) + (char) ('A' + r.nextInt(6));
            boolean usd = r.nextInt(4) == 0;
            BigDecimal costo = r.nextInt(15) == 0 ? new BigDecimal("0.00")
                    : BigDecimal.valueOf(r.nextInt(usd ? 100_000 : 10_000_000)).movePointLeft(2);
            int alicuota = r.nextInt(ALICUOTAS.length);
            List<BigDecimal> recargos = new ArrayList<>();
            BigDecimal suma = BigDecimal.ZERO;
            if (r.nextBoolean()) {
                for (int k = 0; k <= r.nextInt(5); k++) {
                    BigDecimal p;
                    do {
                        p = BigDecimal.valueOf(r.nextInt(19_999) - 9_999).movePointLeft(2);
                    } while (p.signum() == 0);
                    if (CIEN.add(suma).add(p).signum() > 0) {
                        suma = suma.add(p);
                        recargos.add(p);
                    }
                }
            }
            BigDecimal costoReal = recargos.isEmpty() ? null
                    : costo.multiply(CIEN.add(suma)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            // La mitad de los precios a unos centavos del que cubre justo el costo (real o base) con IVA.
            BigDecimal enPesos = (r.nextBoolean() && costoReal != null ? costoReal : costo).multiply(usd ? DOLAR : BigDecimal.ONE);
            BigDecimal precio = r.nextBoolean() ? BigDecimal.valueOf(1 + r.nextInt(10_000_000)).movePointLeft(2)
                    : enPesos.multiply(CIEN.add(new BigDecimal(PORCENTAJES[alicuota]))).divide(CIEN, 2, RoundingMode.HALF_UP)
                    .add(BigDecimal.valueOf(r.nextInt(7) - 3).movePointLeft(2));
            precio = precio.signum() <= 0 ? new BigDecimal("0.01") : precio.min(new BigDecimal("9999999999999.99"));
            boolean activo = r.nextInt(10) != 0;
            StringBuilder json = new StringBuilder("[");
            for (int k = 0; k < recargos.size(); k++) {
                json.append(k == 0 ? "" : ",").append("{\"nombre\":\"R").append(k).append("\",\"porcentaje\":")
                        .append(recargos.get(k).toPlainString()).append('}');
            }
            json.append(']');
            jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final, activo, "
                    + "alicuota_iva, precio_manual, rubro_id, recargos_costo) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ferreteriaId, codigo, descripcion, costo, usd ? "USD" : "ARS", precio, activo, ALICUOTAS[alicuota],
                    r.nextBoolean(), r.nextBoolean() ? null : rubro, json.toString());
            Long id = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                    Long.class, ferreteriaId, codigo);
            cargados.add(new Cargado(id, codigo, descripcion, costo, usd, precio, alicuota, activo, costoReal));
        }
        return cargados;
    }

    /** La regla del aviso, aparte: costo real en pesos y precio sin IVA a 2 decimales HALF_UP; sin dolar, los USD no. */
    private static List<String> esperados(List<Cargado> cargados, BigDecimal dolar) {
        return cargados.stream()
                .filter(c -> c.activo() && (!c.usd() || dolar != null))
                .filter(c -> {
                    BigDecimal real = c.costoReal() == null ? c.costo() : c.costoReal();
                    BigDecimal costoLeido = real.multiply(c.usd() ? dolar : BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal sinIva = c.precio().multiply(CIEN)
                            .divide(CIEN.add(new BigDecimal(PORCENTAJES[c.alicuota()])), 2, RoundingMode.HALF_UP);
                    return costoLeido.compareTo(sinIva) > 0;
                })
                .sorted(Comparator.comparing(Cargado::descripcion).thenComparing(Cargado::id))
                .map(Cargado::codigo)
                .toList();
    }

    private void costoRealDeCadaFila(JsonNode filas, List<Cargado> propios, List<Cargado> ajenos) {
        Map<String, Cargado> porCodigo = new HashMap<>();
        propios.forEach(c -> porCodigo.put(c.codigo(), c));
        List<String> codigosAjenos = ajenos.stream().map(Cargado::codigo).toList();
        for (JsonNode fila : filas) {
            String codigo = fila.get("codigoInterno").asText();
            assertThat(codigosAjenos).doesNotContain(codigo);
            Cargado cargado = porCodigo.get(codigo);
            assertThat(cargado).as(codigo).isNotNull();
            if (cargado.costoReal() == null) {
                assertThat(fila.get("costoReal").isNull()).as(codigo).isTrue();
            } else {
                assertThat(fila.get("costoReal").decimalValue()).as(codigo).isEqualByComparingTo(cargado.costoReal());
            }
        }
    }

    private List<String> codigos(String url, String token) throws Exception {
        return getJson(url, token).findValuesAsText("codigoInterno");
    }
}
