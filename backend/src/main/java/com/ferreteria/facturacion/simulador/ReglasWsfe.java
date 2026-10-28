package com.ferreteria.facturacion.simulador;

import static java.util.Map.entry;

import com.ferreteria.common.Cuit;
import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.Obs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Las validaciones de {@code FECAESolicitar} que copia el simulador, del manual 4.8 ({@value CodigoManual#VERSION_MANUAL}).
 * Función pura: recibe el pedido, la fecha de envío (N), lo que la serie ya tiene autorizado y cómo encontrar un
 * comprobante asociado, y devuelve uno de tres resultados, cada código donde lo pone el manual (p. 25, 76 y ejemplos 3 y 4,
 * p. 83-86): un error de la cabecera ({@code Errors}, sin detalle), el rechazo del comprobante (las validaciones excluyentes
 * del detalle, en sus {@code Observaciones} con R) o la aprobación con sus observaciones no excluyentes.
 *
 * <p>Tolerancias (anexo, p. 202): pasa si el error relativo porcentual es hasta 0,01 % o el absoluto hasta 0,01 (en 10023
 * y 10061, 0,01 por la cantidad de alícuotas). El valor real es el informado; con un real de 0 solo cuenta el absoluto
 * (inferido). El redondeo del servicio es HALF_EVEN y se usa en el cociente del error relativo; el valor calculado no se
 * redondea antes de comparar: redondear BaseImp x alícuota al centavo dejaría pasar el desglose renglón por renglón (dos
 * renglones de $ 100 al 21 % dan 34,72 contra 34,7088, que redondeado es 34,71: 0,01 justo).
 */
final class ReglasWsfe {

    /** Cómo encontrar el ImpTotal de un comprobante asociado que el simulador autorizó (vacío si no lo tiene). */
    interface Asociados extends Function<CbteAsoc, Optional<BigDecimal>> {
    }

    /**
     * Lo que resolvió la validación. Con {@code errores} (de la cabecera) el pedido se rechaza sin detalle; con
     * {@code rechazos} (del comprobante) se rechaza y van en las {@code Observaciones} del detalle; sin ninguno de los dos se
     * aprueba con {@code observaciones}.
     */
    record Veredicto(List<Err> errores, List<Obs> rechazos, List<Obs> observaciones) {

        boolean rechazado() {
            return !errores.isEmpty() || !rechazos.isEmpty();
        }
    }

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal CENTAVO = new BigDecimal("0.01");
    private static final BigDecimal TOLERANCIA_RELATIVA_PORCENTUAL = new BigDecimal("0.01");

    /**
     * {@code FEParamGetTiposIva}: Id y porcentaje. El manual confirma 3 = 0 %, 4 = 10,5 %, 5 = 21 % y 6 = 27 % en sus
     * ejemplos; 8 = 5 % y 9 = 2,5 % son inferidos (confirmar en homologación). Es la tabla de ARCA, no la de
     * {@code facturacion.model.Alicuota}, aunque hoy coincidan.
     */
    private static final Map<Integer, BigDecimal> ALICUOTAS = Map.of(3, BigDecimal.ZERO, 4, new BigDecimal("10.5"),
            5, new BigDecimal("21"), 6, new BigDecimal("27"), 8, new BigDecimal("5"), 9, new BigDecimal("2.5"));

    /**
     * Los {@code DocTipo} de {@code FEParamGetTiposDoc} que nombra el manual (80 CUIT, 86 CUIL, 87 CDI, 96 DNI, 99 sin
     * identificar, y 30, 31, 91 y 94). El manual no trae la lista completa (inferido).
     */
    private static final Set<Integer> TIPOS_DOC = Set.of(30, 31, 80, 86, 87, 91, 94, 96, 99);

    /** DocTipo 80 con este número es el «No Categorizado»: 10015 no lo busca en el padrón. */
    private static final long NO_CATEGORIZADO = 23_000_000_000L;

    /** Notas de débito y de crédito (10197). */
    private static final Set<Integer> DEBITO_O_CREDITO = Set.of(2, 3, 7, 8, 12, 13, 52, 53, 202, 203, 207, 208, 212, 213);

    /** Notas de crédito (10237). */
    private static final Set<Integer> NOTAS_DE_CREDITO = Set.of(3, 8, 13, 53, 203, 208, 213);

    /** Tipos a los que no se aplican 10051 ni 10061 (además de la clase C). */
    private static final Set<Integer> SIN_10051_NI_10061 = Set.of(2, 3, 7, 8, 52, 53);

    /** Tipos sobre los que ARCA observa que correspondía una factura de crédito electrónica (10188). */
    private static final Set<Integer> PUEDEN_SER_FCE = Set.of(1, 4, 6, 9, 11, 15);

    /** Condiciones de un receptor monotributista: Monotributo, Monotributista Social y Monotributo Trabajador Promovido. */
    private static final Set<Integer> MONOTRIBUTO = Set.of(6, 13, 16);

    /** 10040: qué tipos puede llevar en {@code CbtesAsoc} cada {@code CbteTipo}; uno que no está no admite asociados. */
    private static final Map<Integer, Set<Integer>> ASOCIABLES = Map.ofEntries(
            entry(1, Set.of(88, 991)), entry(6, Set.of(88, 991)), entry(51, Set.of(88, 991)),
            entry(2, Set.of(1, 2, 3, 4, 5, 34, 39, 60, 63, 88, 991)), entry(3, Set.of(1, 2, 3, 4, 5, 34, 39, 60, 63, 88, 991)),
            entry(7, Set.of(6, 7, 8, 9, 10, 35, 40, 61, 64, 88, 991)), entry(8, Set.of(6, 7, 8, 9, 10, 35, 40, 61, 64, 88, 991)),
            entry(12, Set.of(11, 12, 13, 15)), entry(13, Set.of(11, 12, 13, 15)),
            entry(52, Set.of(51, 52, 53, 54, 88, 991)), entry(53, Set.of(51, 52, 53, 54, 88, 991)),
            entry(201, Set.of(91, 990, 991, 993, 994, 995)), entry(206, Set.of(91, 990, 991, 993, 994, 995)),
            entry(211, Set.of(91, 990, 991, 993, 994, 995)),
            entry(202, Set.of(201, 202, 203)), entry(203, Set.of(201, 202, 203)),
            entry(207, Set.of(206, 207, 208)), entry(208, Set.of(206, 207, 208)),
            entry(212, Set.of(211, 212, 213)), entry(213, Set.of(211, 212, 213)));

    private final BigDecimal topeRg4444;
    private final BigDecimal minimoFce;

    /**
     * @param topeRg4444 desde este ImpTotal, la Factura B exige identificar al receptor (10015); el manual no da el monto
     * @param minimoFce desde este ImpTotal, a un receptor con CUIT, se observa 10188 (inferido: el manual no da el monto ni
     *     la categorización)
     */
    ReglasWsfe(BigDecimal topeRg4444, BigDecimal minimoFce) {
        this.topeRg4444 = topeRg4444;
        this.minimoFce = minimoFce;
    }

    /**
     * Valida un comprobante de la serie.
     *
     * @param cbteTipo el {@code CbteTipo} de la cabecera
     * @param det el detalle
     * @param hoy la fecha de envío (N), en Buenos Aires
     * @param ultimoNro el último autorizado de la serie (0 si no tiene)
     * @param ultimaCbteFch la CbteFch del último autorizado (null si no tiene)
     * @param asociados cómo encontrar un comprobante asociado
     */
    Veredicto evaluar(int cbteTipo, FeCaeDetRequest det, LocalDate hoy, long ultimoNro, LocalDate ultimaCbteFch,
            Asociados asociados) {
        Optional<ClaseWsfe> deLaClase = ClaseWsfe.de(cbteTipo);
        if (deLaClase.isEmpty()) {
            // 10007 es un control de FeCabReq (p. 40): va en Errors y el pedido no llega a validar el detalle.
            CodigoManual e10007 = CodigoManual.E10007;
            return new Veredicto(List.of(new Err(e10007.codigo(), e10007.texto())), List.of(), List.of());
        }
        ClaseWsfe clase = deLaClase.get();
        SortedSet<CodigoManual> errores = new TreeSet<>((a, b) -> Integer.compare(a.codigo(), b.codigo()));

        if (clase.esA() && det.docTipo() != 80) {
            errores.add(CodigoManual.E10013);
        }
        if (clase == ClaseWsfe.B && !receptorDeLaBIdentificado(det)) {
            errores.add(CodigoManual.E10015);
        }
        validarNumeroYFecha(det, hoy, ultimoNro, ultimaCbteFch, errores);
        validarImportes(cbteTipo, clase, det, errores);
        if (!det.cbtesAsoc().isEmpty()) {
            Set<Integer> admitidos = ASOCIABLES.get(cbteTipo);
            if (admitidos == null || det.cbtesAsoc().stream().anyMatch(a -> !admitidos.contains(a.tipo()))) {
                errores.add(CodigoManual.E10040);
            }
        }
        if (DEBITO_O_CREDITO.contains(cbteTipo) && det.cbtesAsoc().isEmpty() && det.periodoAsoc() == null) {
            errores.add(CodigoManual.E10197);
        }
        Integer condicion = det.condicionIvaReceptorId();
        if (condicion == null) {
            // Obligatoria por la RG 5616 desde la versión 4.8 del manual: el simulador la exige siempre.
            errores.add(CodigoManual.E10246);
        } else if (!ClaseWsfe.condicionExiste(condicion)) {
            errores.add(CodigoManual.E10242);
        } else if (!clase.admite(condicion)) {
            errores.add(CodigoManual.E10243);
        }

        if (!errores.isEmpty()) {
            return new Veredicto(List.of(), errores.stream().map(ReglasWsfe::obs).toList(), List.of());
        }
        return new Veredicto(List.of(), List.of(), observaciones(cbteTipo, clase, det, asociados));
    }

    /** 10016 (número siguiente, rango de la CbteFch y no anterior al último) y 10152 (CbteFch futura en otro mes). */
    private static void validarNumeroYFecha(FeCaeDetRequest det, LocalDate hoy, long ultimoNro, LocalDate ultimaCbteFch,
            SortedSet<CodigoManual> errores) {
        if (det.cbteDesde() != ultimoNro + 1) {
            errores.add(CodigoManual.E10016);
        }
        LocalDate cbteFch = det.cbteFch() != null ? det.cbteFch() : hoy;
        // Límites inclusivos (inferido): N-5 a N+5 para productos, N-10 a N+10 para servicios.
        int margen = det.concepto() == 1 ? 5 : 10;
        if (cbteFch.isBefore(hoy.minusDays(margen)) || cbteFch.isAfter(hoy.plusDays(margen))) {
            errores.add(CodigoManual.E10016);
        }
        if (ultimaCbteFch != null && cbteFch.isBefore(ultimaCbteFch)) {
            errores.add(CodigoManual.E10016);
        }
        if (det.concepto() == 1 && cbteFch.isAfter(hoy) && !YearMonth.from(cbteFch).equals(YearMonth.from(hoy))) {
            errores.add(CodigoManual.E10152);
        }
    }

    /** 10023, 10051, 10061 (no en la C), 10048 y 10071. */
    private static void validarImportes(int cbteTipo, ClaseWsfe clase, FeCaeDetRequest det, SortedSet<CodigoManual> errores) {
        if (clase == ClaseWsfe.C) {
            if (!det.iva().isEmpty()) {
                errores.add(CodigoManual.E10071);
            }
            if (!tolera(det.impTotal(), det.impNeto().add(det.impTrib()), CENTAVO)) {
                errores.add(CodigoManual.E10048);
            }
            return;
        }
        BigDecimal porAlicuota = CENTAVO.multiply(BigDecimal.valueOf(det.iva().size()));
        BigDecimal sumaImporte = det.iva().stream().map(AlicIva::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!tolera(det.impIva(), sumaImporte, porAlicuota)) {
            errores.add(CodigoManual.E10023);
        }
        if (!SIN_10051_NI_10061.contains(cbteTipo)) {
            BigDecimal sumaBase = det.iva().stream().map(AlicIva::baseImp).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!tolera(det.impNeto(), sumaBase, porAlicuota)) {
                errores.add(CodigoManual.E10061);
            }
            // Inferido: un Id que ARCA no tiene no se corresponde con ninguna alícuota.
            if (det.iva().stream().anyMatch(a -> !ALICUOTAS.containsKey(a.id())
                    || !tolera(a.importe(), a.baseImp().multiply(ALICUOTAS.get(a.id())).divide(CIEN), CENTAVO))) {
                errores.add(CodigoManual.E10051);
            }
        }
        BigDecimal suma = det.impTotConc().add(det.impNeto()).add(det.impOpEx()).add(det.impTrib()).add(det.impIva());
        if (!tolera(det.impTotal(), suma, CENTAVO)) {
            errores.add(CodigoManual.E10048);
        }
    }

    /**
     * Las observaciones de un comprobante que se aprueba. El simulador no tiene los padrones de ARCA, así que lo que las
     * dispara es inferido: 10041, un asociado que el simulador no autorizó (todo punto de venta cuenta como electrónico);
     * 10237, una NC por más que la suma de sus asociados, si los tiene a todos; 10063, una A a una CUIT con el dígito
     * verificador mal; 10188, desde el mínimo de la FCE a un receptor con CUIT; 10217, una A a un receptor monotributista.
     */
    private List<Obs> observaciones(int cbteTipo, ClaseWsfe clase, FeCaeDetRequest det, Asociados asociados) {
        SortedSet<CodigoManual> observaciones = new TreeSet<>((a, b) -> Integer.compare(a.codigo(), b.codigo()));

        BigDecimal totalAsociados = BigDecimal.ZERO;
        boolean estanTodos = true;
        for (CbteAsoc asociado : det.cbtesAsoc()) {
            Optional<BigDecimal> total = asociados.apply(asociado);
            if (total.isPresent()) {
                totalAsociados = totalAsociados.add(total.get());
            } else {
                estanTodos = false;
                observaciones.add(CodigoManual.O10041);
            }
        }
        if (NOTAS_DE_CREDITO.contains(cbteTipo) && !det.cbtesAsoc().isEmpty() && estanTodos
                && det.impTotal().compareTo(totalAsociados) > 0) {
            observaciones.add(CodigoManual.O10237);
        }
        if (clase.esA() && det.docTipo() == 80 && !enPadron(det.docNro())) {
            observaciones.add(CodigoManual.O10063);
        }
        if (PUEDEN_SER_FCE.contains(cbteTipo) && det.docTipo() == 80 && det.impTotal().compareTo(minimoFce) >= 0) {
            observaciones.add(CodigoManual.O10188);
        }
        if (clase.esA() && MONOTRIBUTO.contains(det.condicionIvaReceptorId())) {
            observaciones.add(CodigoManual.O10217);
        }
        return observaciones.stream().map(ReglasWsfe::obs).toList();
    }

    /**
     * 10015, individual: DocTipo 99 solo debajo del tope de la RG 4444 y con DocNro 0 (desde el tope, inferido inclusive);
     * 80, 86 u 87 en el padrón (salvo el No Categorizado); cualquier otro, de la lista de ARCA y con número.
     */
    private boolean receptorDeLaBIdentificado(FeCaeDetRequest det) {
        return switch (det.docTipo()) {
            case 99 -> det.impTotal().compareTo(topeRg4444) < 0 && det.docNro() == 0;
            case 80, 86, 87 -> (det.docTipo() == 80 && det.docNro() == NO_CATEGORIZADO) || enPadron(det.docNro());
            default -> TIPOS_DOC.contains(det.docTipo()) && det.docNro() != 0;
        };
    }

    /** El padrón del simulador (inferido): toda CUIT o CUIL con prefijo y dígito verificador válidos. */
    private static boolean enPadron(long docNro) {
        try {
            return Cuit.tienePrefijoValido(Cuit.normalizar(String.format("%011d", docNro)));
        } catch (IllegalArgumentException noEsUnaCuit) {
            return false;
        }
    }

    /** Relativo porcentual hasta 0,01 % o absoluto hasta {@code absolutoMaximo}; el real es el informado. */
    private static boolean tolera(BigDecimal informado, BigDecimal calculado, BigDecimal absolutoMaximo) {
        BigDecimal errorAbsoluto = calculado.subtract(informado).abs();
        if (errorAbsoluto.compareTo(absolutoMaximo) <= 0) {
            return true;
        }
        if (informado.signum() == 0) {
            return false;
        }
        BigDecimal errorRelativoPorcentual = errorAbsoluto.multiply(CIEN).divide(informado.abs(), 10, RoundingMode.HALF_EVEN);
        return errorRelativoPorcentual.compareTo(TOLERANCIA_RELATIVA_PORCENTUAL) <= 0;
    }

    private static Obs obs(CodigoManual codigo) {
        return new Obs(codigo.codigo(), codigo.texto());
    }
}
