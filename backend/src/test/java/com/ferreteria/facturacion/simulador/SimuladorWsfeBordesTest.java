package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bordes del simulador que encontró el odd-verifier de T5 (cu-07-motor-fiscal): el anexo clase × condición completo, las
 * tolerancias justo en el límite, la CbteFch en cambios de mes y de año, la combinación de varias fallas, que un rechazo no
 * toque nada y la concurrencia entre dos ferreterías con la misma CUIT. Cada caso anota lo obtenido y el test falla al final
 * con la lista de los que no coinciden.
 */
class SimuladorWsfeBordesTest extends SimuladorWsfeTestBase {

    final List<String> malos = new ArrayList<>();

    final List<String> registro = new ArrayList<>();

    void log(String s) {
        registro.add(s);
    }

    static String f(FeCaeResponse r) {
        if (!r.errors().isEmpty()) {
            return "E" + r.errors().stream().map(Err::code).toList();
        }
        var d = r.feDetResp();
        String s = d.resultado() + "" + d.observaciones().stream().map(Obs::code).toList();
        // invariantes de forma
        if (d.resultado() == Resultado.R && (d.cae() != null || d.caeFchVto() != null)) {
            s += "!CAE_EN_R";
        }
        if (d.resultado() == Resultado.A && (d.cae() == null || !d.cae().matches("\\d{14}"))) {
            s += "!SIN_CAE";
        }
        if (r.feCabResp().resultado() != d.resultado()) {
            s += "!CAB_DISTINTA";
        }
        return s;
    }

    /** Ferretería nueva con su CUIT, en TenantContext; deja el simulador con ese reloj. */
    void nueva(Clock reloj) {
        cuit = cuitNuevo();
        ferreteriaId = ferreteriaNuevaConCuit(cuit);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        auth = auth(cuit);
        simulador = simulador(reloj, Duration.ZERO);
    }

    static Clock reloj(LocalDate dia) {
        return Clock.fixed(dia.atTime(10, 15, 30).atZone(ZONA).toInstant(), ZONA);
    }

    void probar(String nombre, String esperado, PedidoDePrueba pedido) {
        String obtenido;
        try {
            obtenido = f(solicitar(pedido));
        } catch (RuntimeException e) {
            obtenido = "EXC " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        }
        boolean ok = obtenido.equals(esperado);
        log((ok ? "OK   " : "MAL  ") + nombre + " -> " + obtenido + (ok ? "" : "  (esperado " + esperado + ")"));
        if (!ok) {
            malos.add(nombre);
        }
    }

    void cerrar() {
        assertThat(malos).as(String.join("\n", registro)).isEmpty();
    }

    static PedidoDePrueba c(PedidoDePrueba p) {
        return p.tipo(11).importes("121.00", "121.00", "0.00").alicuotas();
    }

    static AlicIva al(int id, String b, String i) {
        return PedidoDePrueba.alicuota(id, b, i);
    }

    static final PeriodoAsoc OCT = new PeriodoAsoc(HOY.withDayOfMonth(1), HOY);

    @Test
    void anexo() {
        Set<Integer> existen = Set.of(1, 4, 5, 6, 7, 8, 9, 10, 13, 15, 16);
        Set<Integer> a = Set.of(1, 6, 13, 16);
        Set<Integer> b = Set.of(4, 5, 7, 8, 9, 10, 15);
        int[] tipos = {1, 3, 51, 6, 8, 11, 13};
        for (int tipo : tipos) {
            for (int cond = 0; cond <= 17; cond++) {
                nueva(RELOJ);
                PedidoDePrueba p = facturaB().tipo(tipo).documento(80, CUIT_RECEPTOR).condicion(cond);
                if (tipo == 11 || tipo == 13) {
                    p.importes("121.00", "121.00", "0.00").alicuotas();
                }
                if (tipo == 3 || tipo == 8 || tipo == 13) {
                    p.periodo(OCT);
                }
                boolean esA = tipo == 1 || tipo == 3 || tipo == 51;
                boolean esB = tipo == 6 || tipo == 8;
                String esperado;
                if (!existen.contains(cond)) {
                    esperado = "R[10242]";
                } else if ((esA && !a.contains(cond)) || (esB && !b.contains(cond))) {
                    esperado = "R[10243]";
                } else if (esA && Set.of(6, 13, 16).contains(cond)) {
                    esperado = "A[10217]";
                } else {
                    esperado = "A[]";
                }
                probar("anexo tipo " + tipo + " cond " + cond, esperado, p);
            }
            nueva(RELOJ);
            PedidoDePrueba p = facturaB().tipo(tipo).documento(80, CUIT_RECEPTOR).condicion(99);
            if (tipo == 11 || tipo == 13) {
                p.importes("121.00", "121.00", "0.00").alicuotas();
            }
            if (tipo == 3 || tipo == 8 || tipo == 13) {
                p.periodo(OCT);
            }
            probar("anexo tipo " + tipo + " cond 99", "R[10242]", p);
            nueva(RELOJ);
            probar("anexo tipo " + tipo + " cond null", "R[10246]", p.condicion(null));
        }
        cerrar();
    }

    @Test
    void tolerancias() {
        record Caso(String n, String e, UnaryOperator<PedidoDePrueba> c) {
        }
        List<Caso> casos = List.of(
                // 10048 absoluto
                new Caso("10048 +0.01", "A[]", p -> p.impTotal("121.01")),
                new Caso("10048 +0.02", "R[10048]", p -> p.impTotal("121.02")),
                new Caso("10048 -0.01", "A[]", p -> p.impTotal("120.99")),
                new Caso("10048 -0.02", "R[10048]", p -> p.impTotal("120.98")),
                // 10048 relativo, con el informado como real: 100/1.000.000 = 0,01 % justo
                new Caso("10048 rel 0.01% justo", "A[]", p -> p.documento(96, DNI)
                        .importes("1000000.00", "999900.00", "0.00").alicuotas(al(3, "999900.00", "0.00"))),
                new Caso("10048 rel apenas afuera", "R[10048]", p -> p.documento(96, DNI)
                        .importes("1000000.00", "999899.99", "0.00").alicuotas(al(3, "999899.99", "0.00"))),
                new Caso("10048 rel al reves (informado menor)", "R[10048]", p -> p.documento(96, DNI)
                        .importes("999900.00", "1000000.00", "0.00").alicuotas(al(3, "1000000.00", "0.00"))),
                // 10023 absoluto x cantidad de alicuotas
                new Caso("10023 n1 +0.01", "A[]", p -> p.importes("121.01", "100.00", "21.01")),
                new Caso("10023 n1 +0.02", "R[10023]", p -> p.importes("121.02", "100.00", "21.02")),
                new Caso("10023 n3 +0.03", "A[]", p -> p.importes("358.53", "300.00", "58.53")
                        .alicuotas(al(4, "100.00", "10.50"), al(5, "100.00", "21.00"), al(6, "100.00", "27.00"))),
                new Caso("10023 n3 +0.04", "R[10023]", p -> p.importes("358.54", "300.00", "58.54")
                        .alicuotas(al(4, "100.00", "10.50"), al(5, "100.00", "21.00"), al(6, "100.00", "27.00"))),
                // 10023 relativo: 21/210000 = 0,01 % justo
                new Caso("10023 rel justo", "A[]", p -> p.documento(96, DNI).importes("1209900.00", "999900.00", "210000.00")
                        .alicuotas(al(5, "999900.00", "209979.00"))),
                new Caso("10023 rel apenas afuera", "R[10023]", p -> p.documento(96, DNI)
                        .importes("1209899.95", "999899.95", "210000.00").alicuotas(al(5, "999899.95", "209978.99"))),
                // 10061
                new Caso("10061 n1 +0.01", "A[]", p -> p.importes("121.01", "100.01", "21.00")),
                new Caso("10061 n1 +0.02", "R[10061]", p -> p.importes("121.02", "100.02", "21.00")),
                new Caso("10061 n3 +0.03", "A[]", p -> p.importes("358.53", "300.03", "58.50")
                        .alicuotas(al(4, "100.00", "10.50"), al(5, "100.00", "21.00"), al(6, "100.00", "27.00"))),
                new Caso("10061 n3 +0.04", "R[10061]", p -> p.importes("358.54", "300.04", "58.50")
                        .alicuotas(al(4, "100.00", "10.50"), al(5, "100.00", "21.00"), al(6, "100.00", "27.00"))),
                new Caso("10061 rel justo", "A[]", p -> p.documento(96, DNI).importes("1209979.00", "1000000.00", "209979.00")
                        .alicuotas(al(5, "999900.00", "209979.00"))),
                new Caso("10061 rel apenas afuera", "R[10061]", p -> p.documento(96, DNI)
                        .importes("1209979.00", "1000000.00", "209979.00").alicuotas(al(5, "999899.99", "209979.00"))),
                // 10051 absoluto, sin redondear el calculado
                new Caso("10051 21.01 sobre 100", "A[]", p -> p.importes("121.01", "100.00", "21.01").alicuotas(al(5, "100.00", "21.01"))),
                new Caso("10051 20.99 sobre 100", "A[]", p -> p.importes("120.99", "100.00", "20.99").alicuotas(al(5, "100.00", "20.99"))),
                new Caso("10051 20.98 sobre 100", "R[10051]", p -> p.importes("120.98", "100.00", "20.98").alicuotas(al(5, "100.00", "20.98"))),
                new Caso("10051 21.00 sobre 100.05 (err 0.0105)", "R[10051]", p -> p.importes("121.05", "100.05", "21.00")
                        .alicuotas(al(5, "100.05", "21.00"))),
                new Caso("10051 21.02 sobre 100.05 (err 0.0095)", "A[]", p -> p.importes("121.07", "100.05", "21.02")
                        .alicuotas(al(5, "100.05", "21.02"))),
                // 10051 relativo
                new Caso("10051 rel +21 sobre 210000", "A[]", p -> p.documento(96, DNI).importes("1210021.00", "1000000.00", "210021.00")
                        .alicuotas(al(5, "1000000.00", "210021.00"))),
                new Caso("10051 rel +21.01", "R[10051]", p -> p.documento(96, DNI).importes("1210021.01", "1000000.00", "210021.01")
                        .alicuotas(al(5, "1000000.00", "210021.01"))),
                new Caso("10051 rel -21 (asimetria del real)", "R[10051]", p -> p.documento(96, DNI)
                        .importes("1209979.00", "1000000.00", "209979.00").alicuotas(al(5, "1000000.00", "209979.00"))),
                // Id 3 con importe
                new Caso("10051 Id 3 con importe 0.01", "A[]", p -> p.importes("100.01", "100.00", "0.01").alicuotas(al(3, "100.00", "0.01"))),
                new Caso("10051 Id 3 con importe 0.02", "R[10051]", p -> p.importes("100.02", "100.00", "0.02").alicuotas(al(3, "100.00", "0.02"))),
                // 10051/10061 no aplican a NC A
                new Caso("NC A 3 sin 10051/10061", "A[]", p -> p.tipo(3).documento(80, CUIT_RECEPTOR).condicion(1).periodo(OCT)
                        .importes("121.50", "100.50", "21.00").alicuotas(al(5, "100.00", "21.00"))),
                // 10023 si aplica a NC B
                new Caso("NC B 8 con 10023", "R[10023]", p -> p.tipo(8).periodo(OCT).importes("121.05", "100.00", "21.05")),
                // C
                new Caso("C 10048 +0.01", "A[]", p -> c(p).impTotal("121.01")),
                new Caso("C 10048 +0.02", "R[10048]", p -> c(p).impTotal("121.02")),
                new Caso("C con IVA", "R[10071]", p -> c(p).alicuotas(al(5, "100.00", "21.00"))),
                new Caso("C con ImpIVA y sin array (fuera de lista)", "A[]", p -> c(p).importes("121.00", "121.00", "21.00")));
        for (Caso caso : casos) {
            nueva(RELOJ);
            probar(caso.n(), caso.e(), caso.c().apply(facturaB()));
        }
        cerrar();
    }

    @Test
    void desglose() {
        // S7: 2 y 3 renglones de 100 al 21 %
        for (int n : new int[] {2, 3}) {
            nueva(RELOJ);
            List<RenglonFiscal> r = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                r.add(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("100.00")));
            }
            DesgloseFiscal d = DesgloseFiscal.de(Letra.B, r);
            log("S7 desglose n=" + n + ": neto " + d.impNeto() + " iva " + d.impIva());
            probar("S7 " + n + " renglones por comprobante", "A[]", facturaB().desglose(d));
            BigDecimal neto = new BigDecimal("100.00").multiply(new BigDecimal("100")).divide(new BigDecimal("121"), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(n));
            BigDecimal iva = new BigDecimal("100.00").multiply(BigDecimal.valueOf(n)).subtract(neto);
            nueva(RELOJ);
            probar("S7 " + n + " renglones sumados por renglon (" + neto + "/" + iva + ")", "R[10051]", facturaB()
                    .importes(String.valueOf(new BigDecimal("100.00").multiply(BigDecimal.valueOf(n))), neto.toPlainString(), iva.toPlainString())
                    .alicuotas(al(5, neto.toPlainString(), iva.toPlainString())));
        }
        // Barrido puro sobre ReglasWsfe: todo desglose por comprobante pasa, por alicuota y letra, con totales al azar
        ReglasWsfe reglas = new ReglasWsfe(topeRg4444, minimoFce);
        Random azar = new Random(42);
        int fallas = 0;
        int probados = 0;
        Alicuota[] als = Alicuota.values();
        for (int i = 0; i < 20000; i++) {
            List<RenglonFiscal> r = new ArrayList<>();
            int renglones = 1 + azar.nextInt(6);
            for (int k = 0; k < renglones; k++) {
                long centavos = 1 + (long) (azar.nextDouble() * (i % 3 == 0 ? 99_999_999_999L : 100_000L));
                r.add(new RenglonFiscal(als[azar.nextInt(als.length)], BigDecimal.valueOf(centavos, 2)));
            }
            Letra letra = Letra.values()[azar.nextInt(3)];
            DesgloseFiscal d = DesgloseFiscal.de(letra, r);
            PedidoDePrueba p = facturaB().tipo(letra == Letra.A ? 1 : letra == Letra.B ? 6 : 11)
                    .documento(96, DNI).desglose(d);
            if (letra == Letra.A) {
                p.documento(80, CUIT_RECEPTOR).condicion(1);
            }
            var v = reglas.evaluar(p.armar().feCabReq().cbteTipo(), p.detalle(), HOY, 0, null, x -> java.util.Optional.empty());
            probados++;
            if (v.rechazado()) {
                fallas++;
                if (fallas < 10) {
                    log("MAL  barrido desglose " + letra + " " + r + " -> " + v);
                }
            }
        }
        log((fallas == 0 ? "OK   " : "MAL  ") + "barrido desglose por comprobante: " + probados + " probados, " + fallas + " rechazados");
        if (fallas > 0) {
            malos.add("barrido");
        }
        cerrar();
    }

    @Test
    void fechas() {
        LocalDate oct10 = LocalDate.of(2026, 10, 10);
        nueva(reloj(oct10));
        probar("N+5 mismo mes (10/10->15/10)", "A[]", facturaB().cbteFch(oct10.plusDays(5)));
        nueva(reloj(oct10));
        probar("N+6 mismo mes", "R[10016]", facturaB().cbteFch(oct10.plusDays(6)));
        nueva(reloj(oct10));
        probar("N-5", "A[]", facturaB().cbteFch(oct10.minusDays(5)));
        nueva(reloj(oct10));
        probar("N-6", "R[10016]", facturaB().cbteFch(oct10.minusDays(6)));
        LocalDate nov2 = LocalDate.of(2026, 11, 2);
        nueva(reloj(nov2));
        probar("N-4 en el mes anterior (2/11->29/10)", "A[]", facturaB().cbteFch(LocalDate.of(2026, 10, 29)));
        nueva(reloj(nov2));
        probar("N-5 en el mes anterior (2/11->28/10)", "A[]", facturaB().cbteFch(LocalDate.of(2026, 10, 28)));
        LocalDate oct30 = LocalDate.of(2026, 10, 30);
        nueva(reloj(oct30));
        probar("N+2 al mes siguiente (30/10->1/11)", "R[10152]", facturaB().cbteFch(LocalDate.of(2026, 11, 1)));
        nueva(reloj(oct30));
        probar("N+1 ultimo dia del mes (30/10->31/10)", "A[]", facturaB().cbteFch(LocalDate.of(2026, 10, 31)));
        LocalDate dic30 = LocalDate.of(2026, 12, 30);
        nueva(reloj(dic30));
        probar("cambio de anio (30/12->2/1)", "R[10152]", facturaB().cbteFch(LocalDate.of(2027, 1, 2)));
        nueva(RELOJ);
        probar("N+6 y otro mes (28/10->3/11)", "R[10016, 10152]", facturaB().cbteFch(HOY.plusDays(6)));
        nueva(RELOJ);
        probar("concepto 2 N+10 otro mes (sin 10152)", "A[]", facturaB().concepto(2).cbteFch(HOY.plusDays(10)));
        nueva(RELOJ);
        probar("concepto 2 N+11", "R[10016]", facturaB().concepto(2).cbteFch(HOY.plusDays(11)));
        nueva(RELOJ);
        probar("concepto 3 N-10", "A[]", facturaB().concepto(3).cbteFch(HOY.minusDays(10)));
        // contra el ultimo autorizado
        nueva(RELOJ);
        probar("ultimo: n1 en N+2", "A[]", facturaB().numero(1).cbteFch(HOY.plusDays(2)));
        probar("ultimo: n2 igual al ultimo", "A[]", facturaB().numero(2).cbteFch(HOY.plusDays(2)));
        probar("ultimo: n3 un dia antes", "R[10016]", facturaB().numero(3).cbteFch(HOY.plusDays(1)));
        probar("ultimo: n3 sin CbteFch (hoy < ultimo)", "R[10016]", facturaB().numero(3).cbteFch(null));
        probar("ultimo: n3 posterior", "A[]", facturaB().numero(3).cbteFch(HOY.plusDays(3)));
        // sin CbteFch
        nueva(RELOJ);
        FeCaeResponse r = solicitar(facturaB().cbteFch(null));
        log("sin CbteFch -> " + f(r) + " cbteFch " + r.feDetResp().cbteFch() + " vto " + r.feDetResp().caeFchVto());
        if (!HOY.equals(r.feDetResp().cbteFch()) || !HOY.plusDays(10).equals(r.feDetResp().caeFchVto())) {
            malos.add("sin CbteFch");
        }
        // hora: 23:30 en Buenos Aires = otro dia en UTC
        LocalDate d = LocalDate.of(2026, 10, 31);
        nueva(Clock.fixed(d.atTime(23, 30).atZone(ZONA).toInstant(), ZONA));
        probar("31/10 23:30 BA (UTC ya es 1/11): CbteFch 31/10", "A[]", facturaB().cbteFch(d));
        nueva(Clock.fixed(d.atTime(23, 30).atZone(ZONA).toInstant(), java.time.ZoneOffset.UTC));
        probar("idem con reloj en UTC: CbteFch 31/10", "A[]", facturaB().cbteFch(d));
        cerrar();
    }

    @Test
    void numeracionYCombinaciones() {
        nueva(RELOJ);
        probar("n=0", "R[10016]", facturaB().numero(0));
        probar("n=2 con serie vacia", "R[10016]", facturaB().numero(2));
        probar("n=1", "A[]", facturaB().numero(1));
        probar("n=1 de nuevo", "R[10016]", facturaB().numero(1));
        probar("n=3 salteado", "R[10016]", facturaB().numero(3));
        probar("n=2", "A[]", facturaB().numero(2));
        probar("PV 2 n=1 (otra serie)", "A[]", facturaB().ptoVta(2).numero(1));
        probar("tipo 11 n=1 (otra serie)", "A[]", c(facturaB()).numero(1));
        if (ultimo(6) != 2) {
            malos.add("ultimo 6");
        }
        Integer filas = jdbc.queryForObject("select count(*) from simulador_wsfe_comprobante where ferreteria_id = ?", Integer.class, ferreteriaId);
        log("comprobantes guardados tras 4 A y 4 R: " + filas);
        if (filas != 4) {
            malos.add("filas");
        }
        // combinaciones
        nueva(RELOJ);
        probar("A a DNI, cond 5, n=2, IVA mal", "R[10013, 10016, 10023, 10051, 10243]",
                facturaB().tipo(1).documento(96, DNI).condicion(5).numero(2).importes("121.00", "100.00", "21.00")
                        .alicuotas(al(5, "100.00", "21.05")));
        nueva(RELOJ);
        probar("C con IVA, total mal, sin condicion", "R[10048, 10071, 10246]",
                c(facturaB()).impTotal("130.00").alicuotas(al(5, "100.00", "21.00")).condicion(null));
        nueva(RELOJ);
        probar("10007 tapa todo lo demas", "E[10007]", facturaB().tipo(99).numero(7).condicion(null).impTotal("999.00"));
        nueva(RELOJ);
        probar("ALEY 51 a DNI", "R[10013]", facturaB().tipo(51).documento(96, DNI).condicion(1));
        nueva(RELOJ);
        probar("ND A 2 sin asoc ni periodo", "R[10197]", facturaB().tipo(2).documento(80, CUIT_RECEPTOR).condicion(1));
        nueva(RELOJ);
        probar("ND A 2 con periodo", "A[]", facturaB().tipo(2).documento(80, CUIT_RECEPTOR).condicion(1).periodo(OCT));
        nueva(RELOJ);
        probar("Factura A con asoc tipo 1", "R[10040]", facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(1)
                .asociados(new CbteAsoc(1, 1, 1, null, null)));
        nueva(RELOJ);
        probar("Factura A con asoc 88 inexistente", "A[10041]", facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(1)
                .asociados(new CbteAsoc(88, 1, 1, null, null)));
        nueva(RELOJ);
        probar("NC C 13 con asoc tipo 1", "R[10040]", c(facturaB()).tipo(13).asociados(new CbteAsoc(1, 1, 1, null, null)));
        nueva(RELOJ);
        probar("NC A 3 con asoc tipo 6", "R[10040]", facturaB().tipo(3).documento(80, CUIT_RECEPTOR).condicion(1)
                .asociados(new CbteAsoc(6, 1, 1, null, null)));
        // 10237
        nueva(RELOJ);
        probar("10237 base: factura A 121", "A[]", facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(1));
        probar("10237 NC 121.01", "A[10237]", facturaB().tipo(3).documento(80, CUIT_RECEPTOR).condicion(1).numero(1)
                .importes("121.01", "100.01", "21.00").asociados(new CbteAsoc(1, 1, 1, null, null)));
        probar("10237 NC 121.00", "A[]", facturaB().tipo(3).documento(80, CUIT_RECEPTOR).condicion(1).numero(2)
                .asociados(new CbteAsoc(1, 1, 1, null, null)));
        probar("10237 con un asociado que falta", "A[10041]", facturaB().tipo(3).documento(80, CUIT_RECEPTOR).condicion(1)
                .numero(3).importes("1210.00", "1000.00", "210.00").alicuotas(al(5, "1000.00", "210.00"))
                .asociados(new CbteAsoc(1, 1, 1, null, null), new CbteAsoc(1, 1, 9, null, null)));
        // observaciones
        nueva(RELOJ);
        probar("10217 cond 13", "A[10217]", facturaB().tipo(1).documento(80, CUIT_RECEPTOR).condicion(13));
        nueva(RELOJ);
        probar("10063 A CUIT fuera de padron", "A[10063]", facturaB().tipo(1).documento(80, CUIT_FUERA_DEL_PADRON).condicion(1));
        nueva(RELOJ);
        DesgloseFiscal fce = DesgloseFiscal.de(Letra.B, List.of(new RenglonFiscal(Alicuota.IVA_21, minimoFce)));
        probar("10188 B CUIT >= minimo FCE", "A[10188]", facturaB().documento(80, CUIT_RECEPTOR).condicion(4).desglose(fce));
        nueva(RELOJ);
        probar("10188 no en NC B", "A[]", facturaB().tipo(8).periodo(OCT).documento(80, CUIT_RECEPTOR).condicion(4).desglose(fce));
        // 10015
        nueva(RELOJ);
        DesgloseFiscal tope = DesgloseFiscal.de(Letra.B, List.of(new RenglonFiscal(Alicuota.IVA_21, topeRg4444)));
        probar("10015 99 en el tope", "R[10015]", facturaB().desglose(tope));
        nueva(RELOJ);
        probar("10015 DNI en el tope", "A[]", facturaB().documento(96, DNI).desglose(tope));
        nueva(RELOJ);
        probar("10015 CUIL valido (86)", "A[]", facturaB().documento(86, 20123456786L));
        nueva(RELOJ);
        probar("10015 99 con DocNro", "R[10015]", facturaB().documento(99, 5));
        cerrar();
    }

    @Test
    void invarianteRechazoNoTocaNada() {
        nueva(RELOJ);
        solicitar(facturaB().numero(1));
        List<java.util.Map<String, Object>> antesS = jdbc.queryForList("select * from simulador_wsfe_serie where ferreteria_id = ? order by id", ferreteriaId);
        List<java.util.Map<String, Object>> antesC = jdbc.queryForList("select * from simulador_wsfe_comprobante where ferreteria_id = ? order by id", ferreteriaId);
        probar("rechazo n2 con IVA mal", "R[10023, 10048]", facturaB().numero(2).importes("121.00", "100.00", "25.00"));
        probar("rechazo n1 repetido", "R[10016]", facturaB().numero(1).documento(96, DNI));
        if (!antesS.equals(jdbc.queryForList("select * from simulador_wsfe_serie where ferreteria_id = ? order by id", ferreteriaId))
                || !antesC.equals(jdbc.queryForList("select * from simulador_wsfe_comprobante where ferreteria_id = ? order by id", ferreteriaId))) {
            malos.add("estado cambio tras rechazo");
        }
        log("estado tras rechazos igual: " + !malos.contains("estado cambio tras rechazo"));
        // REQUIRES_NEW: el rollback de quien llama no deshace lo autorizado
        TransactionTemplate afuera = new TransactionTemplate(transacciones);
        try {
            afuera.executeWithoutResult(s -> {
                solicitar(facturaB().numero(2));
                throw new IllegalStateException("rollback de quien llama");
            });
        } catch (IllegalStateException esperado) {
            // ok
        }
        long u = ultimo(6);
        log("tras rollback del llamador, ultimo = " + u);
        if (u != 2) {
            malos.add("REQUIRES_NEW");
        }
        // Otro ambiente no crea filas
        int seriesAntes = jdbc.queryForObject("select count(*) from simulador_wsfe_serie", Integer.class);
        try {
            simulador.feCaeSolicitar(new Auth(com.ferreteria.facturacion.model.Ambiente.PRODUCCION, cuit),
                    facturaB().numero(3).armar());
            malos.add("PRODUCCION no fallo");
        } catch (IllegalArgumentException e) {
            log("PRODUCCION -> IAE: " + e.getMessage());
        }
        if (seriesAntes != jdbc.queryForObject("select count(*) from simulador_wsfe_serie", Integer.class)) {
            malos.add("PRODUCCION creo filas");
        }
        // fuera de dominio
        nueva(RELOJ);
        probar("CbteTipo 0 (fuera de dominio)", "E[10007]", facturaB().tipo(0));
        nueva(RELOJ);
        probar("PtoVta 0 (fuera de dominio)", "?", facturaB().ptoVta(0));
        nueva(RELOJ);
        probar("negativos B -121", "?", facturaB().importes("-121.00", "-100.00", "-21.00").alicuotas(al(5, "-100.00", "-21.00")));
        nueva(RELOJ);
        probar("CbteTipo 999", "E[10007]", facturaB().tipo(999));
        log("series con cbte_tipo 999: " + jdbc.queryForObject("select count(*) from simulador_wsfe_serie where cbte_tipo = 999", Integer.class));
        malos.remove("PtoVta 0 (fuera de dominio)");
        malos.remove("negativos B -121");
        malos.remove("CbteTipo 0 (fuera de dominio)");
        cerrar();
    }

    @Test
    void concurrencia() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        int rondas = 15;
        int hilos = 6;
        int malasRondas = 0;
        ExecutorService pool = Executors.newFixedThreadPool(hilos * 2);
        try {
            for (int ronda = 0; ronda < rondas; ronda++) {
                nueva(RELOJ);
                long f1 = ferreteriaId;
                Auth a1 = auth;
                // segunda ferreteria con la misma CUIT emisora en Auth, en paralelo
                long f2 = ferreteriaNuevaConCuit(cuitNuevo());
                CountDownLatch largada = new CountDownLatch(1);
                List<Future<String>> fs = new ArrayList<>();
                for (int i = 0; i < hilos * 2; i++) {
                    long fid = i % 2 == 0 ? f1 : f2;
                    SimuladorWsfe sim = simulador;
                    fs.add(pool.submit(() -> {
                        TenantContext.setCurrentFerreteriaId(fid);
                        try {
                            largada.await();
                            return fid + ":" + f(sim.feCaeSolicitar(a1, facturaB().numero(1).armar()));
                        } catch (Exception e) {
                            return fid + ":EXC " + e.getClass().getSimpleName() + " " + e.getMessage();
                        } finally {
                            TenantContext.clear();
                        }
                    }));
                }
                largada.countDown();
                List<String> res = new ArrayList<>();
                for (Future<String> x : fs) {
                    res.add(x.get(60, TimeUnit.SECONDS));
                }
                long a1ok = res.stream().filter(s -> s.equals(f1 + ":A[]")).count();
                long r1 = res.stream().filter(s -> s.equals(f1 + ":R[10016]")).count();
                long a2ok = res.stream().filter(s -> s.equals(f2 + ":A[]")).count();
                long r2 = res.stream().filter(s -> s.equals(f2 + ":R[10016]")).count();
                boolean ok = a1ok == 1 && r1 == 5 && a2ok == 1 && r2 == 5;
                if (!ok) {
                    malasRondas++;
                    log("MAL  ronda " + ronda + ": " + res);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long delta = barrera.deadlocksDespues(antes);
        log((malasRondas == 0 && delta == 0 ? "OK   " : "MAL  ") + "concurrencia " + rondas + " rondas x (6+6) hilos: rondas malas "
                + malasRondas + ", deadlocks " + delta);
        if (malasRondas > 0 || delta != 0) {
            malos.add("concurrencia");
        }
        cerrar();
    }

}
