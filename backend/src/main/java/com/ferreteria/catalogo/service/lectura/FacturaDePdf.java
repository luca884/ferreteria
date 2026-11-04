package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.FacturaLeida.Control;
import com.ferreteria.catalogo.api.FacturaLeida.Encabezado;
import com.ferreteria.catalogo.api.FacturaLeida.Recargo;
import com.ferreteria.catalogo.api.FacturaLeida.Renglon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta las palabras con posicion de una factura de proveedor en PDF. Puro: no abre el PDF. A diferencia de una
 * lista de precios, la factura no es una tabla de punta a punta (el encabezado y el pie se repiten en cada pagina), asi
 * que se lee por linea, con las lineas de {@link TablaDePdf#lineas}:
 * <ul>
 *   <li>un renglon es una linea cuya primera palabra es el codigo y que trae tres numeros, cantidad, unitario y neto,
 *       con cantidad x unitario = neto (con una bonificacion en % entre el unitario y el neto, tambien);</li>
 *   <li>un recargo es una linea despues del ultimo renglon con un % y un importe que es ese % de la suma de netos,
 *       que no es un impuesto (IVA, percepciones, IIBB...); un descuento o bonificacion es un recargo negativo;</li>
 *   <li>la lectura cuadra si despues del ultimo renglon hay un total igual a la suma de netos mas los recargos.</li>
 * </ul>
 * Los numeros pueden venir en formato US (1,234.56) o argentino (1.234,56): se elige el que usa la mayoria del
 * documento.
 */
final class FacturaDePdf {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    /** Diferencia de centavos que se tolera al verificar una cuenta contra lo impreso. */
    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");

    private static final Pattern NUMERO_US = Pattern.compile("-?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?");
    private static final Pattern NUMERO_AR = Pattern.compile("-?(\\d{1,3}(\\.\\d{3})+|\\d+)(,\\d+)?");
    private static final Pattern PUNTO_DE_VENTA_Y_NUMERO = Pattern.compile("(?<!\\d)(\\d{4,5})\\s*-\\s*(\\d{8})(?!\\d)");
    private static final Pattern CODIGO_DE_COMPROBANTE = Pattern.compile("(?i)c[oó]d(?:igo|\\.)?\\s*:?\\s*(\\d{2})(?!\\d)");
    private static final Pattern FECHA = Pattern.compile("(?<!\\d)(\\d{2})/(\\d{2})/(\\d{4})(?!\\d)");
    /** Una etiqueta que deja la fecha que sigue como otra cosa (inicio de actividades, vencimiento): no es la emision. */
    private static final Pattern NO_ES_LA_FECHA = Pattern.compile("(?i)INIC|ACTIV|\\bACT\\.|VTO|VENC");
    /** El comprobante en el formato de ARCA en linea: «Punto de Venta: 00002 Comp. Nro: 00000123». */
    private static final Pattern PUNTO_DE_VENTA_Y_NUMERO_ARCA = Pattern.compile(
        "punto\\s+de\\s+venta[\\s.:]*(\\d{1,5})\\s+comp(?:robante)?[\\s.:]*(?:nro|n[º°o]|n[uú]mero)?[\\s.:]*(\\d{1,8})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Un nombre de impuesto (IVA, percepciones, IIBB, impuestos internos, ganancias), ya sin acentos y en minusculas. */
    private static final Pattern ES_UN_IMPUESTO = Pattern.compile(
        "\\biva\\b|\\bi\\.v\\.a|percep|\\biibb\\b|i\\.i\\.b\\.b|ingresos\\s+brutos|impuestos?\\s+internos?|ganancias");
    /** Un nombre que dice que el cargo resta: descuento o bonificacion. */
    private static final Pattern ES_UN_DESCUENTO = Pattern.compile("descuento|bonificaci|bonif");
    private static final Pattern CUIT = Pattern.compile("(?<!\\d)(?:\\d{2}-\\d{8}-\\d|\\d{11})(?!\\d)");
    private static final int[] PESOS_DEL_CUIT = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private enum Formato { US, AR }

    /** Una palabra de la linea: su texto, su valor si es un numero (con o sin %) y si llevaba el %. */
    private record Palabra(String texto, BigDecimal valor, boolean porcentaje) {
        boolean esNumero() {
            return valor != null && !porcentaje;
        }
    }

    private record Linea(List<Palabra> palabras, String texto) {
    }

    private FacturaDePdf() {
    }

    static FacturaLeida leer(List<PalabraPdf> palabras) {
        List<List<PalabraPdf>> crudas = TablaDePdf.lineas(palabras);
        if (crudas.size() > LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
            throw LimitesDeLista.demasiadasFilas();
        }
        Formato formato = formatoDelDocumento(crudas);
        List<Linea> lineas = crudas.stream().map(linea -> linea(linea, formato)).toList();

        int primero = -1;
        int ultimo = -1;
        List<Renglon> renglones = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            Optional<Renglon> renglon = renglon(lineas.get(i).palabras());
            if (renglon.isPresent()) {
                renglones.add(renglon.get());
                primero = primero < 0 ? i : primero;
                ultimo = i;
            }
        }

        List<Linea> encabezado = lineas.subList(0, primero < 0 ? lineas.size() : primero);
        if (renglones.isEmpty()) {
            return new FacturaLeida(encabezado(encabezado), List.of(), List.of(), BigDecimal.ZERO.setScale(2),
                Control.SIN_CONTROL);
        }
        BigDecimal suma = renglones.stream().map(Renglon::neto).reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        List<Linea> pie = lineas.subList(ultimo + 1, lineas.size());
        List<Recargo> recargos = recargos(pie, suma);
        return new FacturaLeida(encabezado(encabezado), renglones, recargos, suma, control(pie, suma, recargos));
    }

    // ---- numeros ----

    /** El formato que usan mas numeros sin ambiguedad; con un empate, el argentino. */
    private static Formato formatoDelDocumento(List<List<PalabraPdf>> lineas) {
        int us = 0;
        int ar = 0;
        for (List<PalabraPdf> linea : lineas) {
            for (int i = 1; i < linea.size(); i++) {
                String texto = sinPorcentaje(linea.get(i).texto());
                boolean esUs = NUMERO_US.matcher(texto).matches();
                boolean esAr = NUMERO_AR.matcher(texto).matches();
                if (esUs && !esAr) {
                    us++;
                } else if (esAr && !esUs) {
                    ar++;
                }
            }
        }
        return us > ar ? Formato.US : Formato.AR;
    }

    private static String sinPorcentaje(String texto) {
        return texto.endsWith("%") ? texto.substring(0, texto.length() - 1) : texto;
    }

    private static BigDecimal numero(String texto, Formato formato) {
        String limpio = sinPorcentaje(texto);
        if (formato == Formato.US) {
            return NUMERO_US.matcher(limpio).matches() ? new BigDecimal(limpio.replace(",", "")) : null;
        }
        return NUMERO_AR.matcher(limpio).matches() ? new BigDecimal(limpio.replace(".", "").replace(',', '.')) : null;
    }

    /** Las palabras de la linea; un «%» suelto se pega al numero que lo precede. */
    private static Linea linea(List<PalabraPdf> cruda, Formato formato) {
        List<Palabra> palabras = new ArrayList<>();
        for (PalabraPdf pdf : cruda) {
            String texto = pdf.texto();
            if (texto.equals("%") && !palabras.isEmpty() && palabras.get(palabras.size() - 1).esNumero()) {
                Palabra anterior = palabras.remove(palabras.size() - 1);
                palabras.add(new Palabra(anterior.texto() + "%", anterior.valor(), true));
                continue;
            }
            palabras.add(new Palabra(texto, numero(texto, formato), texto.endsWith("%")));
        }
        return new Linea(palabras, String.join(" ", cruda.stream().map(PalabraPdf::texto).toList()));
    }

    private static BigDecimal aCentavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean coinciden(BigDecimal calculado, BigDecimal impreso) {
        return aCentavos(calculado).subtract(impreso).abs().compareTo(TOLERANCIA) <= 0;
    }

    // ---- renglones ----

    /**
     * Cantidad, unitario y neto: tres numeros de la linea (despues del codigo) con cantidad x unitario = neto. Gana el
     * neto mas a la derecha, con el unitario mas cercano y la cantidad mas cercana al codigo.
     */
    private static Optional<Renglon> renglon(List<Palabra> palabras) {
        List<Integer> numeros = new ArrayList<>();
        for (int i = 1; i < palabras.size(); i++) {
            if (palabras.get(i).esNumero()) {
                numeros.add(i);
            }
        }
        for (int k = numeros.size() - 1; k >= 2; k--) {
            for (int j = k - 1; j >= 1; j--) {
                List<BigDecimal> bonificaciones = bonificaciones(palabras, numeros.get(j), numeros.get(k));
                if (bonificaciones.size() > 1) {
                    continue;
                }
                BigDecimal bonificacion = bonificaciones.stream().findFirst().orElse(BigDecimal.ZERO);
                for (int i = 0; i < j; i++) {
                    Optional<Renglon> renglon = terna(palabras, numeros.get(i), numeros.get(j), numeros.get(k),
                        bonificacion);
                    if (renglon.isPresent()) {
                        return renglon;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Los % entre el unitario y el neto: ninguno si el renglon no tiene bonificacion. */
    private static List<BigDecimal> bonificaciones(List<Palabra> palabras, int unitario, int neto) {
        List<BigDecimal> bonificaciones = new ArrayList<>();
        for (int i = unitario + 1; i < neto; i++) {
            if (palabras.get(i).porcentaje() && palabras.get(i).valor() != null) {
                bonificaciones.add(palabras.get(i).valor());
            }
        }
        return bonificaciones;
    }

    private static Optional<Renglon> terna(List<Palabra> palabras, int iq, int iu, int in, BigDecimal bonificacion) {
        BigDecimal q = palabras.get(iq).valor();
        BigDecimal u = palabras.get(iu).valor();
        BigDecimal n = palabras.get(in).valor();
        if (q.signum() <= 0 || u.signum() <= 0 || n.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal factor = CIEN.subtract(bonificacion).divide(CIEN);
        BigDecimal unitario = u.multiply(factor);
        if (!coinciden(q.multiply(unitario), n)) {
            return Optional.empty();
        }
        String descripcion = String.join(" ",
            palabras.subList(iq + 1, iu).stream().map(Palabra::texto).toList());
        return Optional.of(new Renglon(palabras.get(0).texto(), q, descripcion, aCentavos(unitario), aCentavos(n)));
    }

    // ---- recargos y control ----

    /**
     * Un % y un importe que es ese % de la suma de netos. Los impuestos (IVA, percepciones, IIBB, impuestos internos,
     * ganancias) nunca son recargos del costo. El signo sale del importe impreso, del % impreso o del nombre
     * (descuento, bonificacion): cualquiera de los tres lo vuelve negativo.
     */
    private static List<Recargo> recargos(List<Linea> pie, BigDecimal suma) {
        List<Recargo> recargos = new ArrayList<>();
        for (Linea linea : pie) {
            List<Palabra> palabras = linea.palabras();
            for (int p = 0; p < palabras.size(); p++) {
                Palabra porcentaje = palabras.get(p);
                if (!porcentaje.porcentaje() || porcentaje.valor() == null || porcentaje.valor().signum() == 0) {
                    continue;
                }
                List<Palabra> antes = palabras.subList(0, p);
                String nombre = nombre(antes);
                if (esUnImpuesto(nombre)) {
                    break;
                }
                BigDecimal esperado = suma.multiply(porcentaje.valor().abs()).divide(CIEN);
                palabras.stream().filter(Palabra::esNumero)
                    .filter(importe -> coinciden(esperado, importe.valor().abs())).findFirst()
                    .ifPresent(importe -> {
                        boolean resta = porcentaje.valor().signum() < 0 || importe.valor().signum() < 0
                            || esUnDescuento(nombre);
                        BigDecimal signo = resta ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                        recargos.add(new Recargo(nombre, aCentavos(porcentaje.valor().abs().multiply(signo)),
                            aCentavos(importe.valor().abs().multiply(signo))));
                    });
                break;
            }
        }
        return recargos;
    }

    /** Las palabras antes del %, sin los «:». */
    private static String nombre(List<Palabra> antes) {
        String nombre = String.join(" ", antes.stream().map(Palabra::texto).filter(t -> !t.equals(":")).toList())
            .replaceAll("\\s*:$", "").trim();
        return nombre.isEmpty() ? "Recargo" : nombre;
    }

    private static String sinAcentos(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static boolean esUnImpuesto(String nombre) {
        return ES_UN_IMPUESTO.matcher(sinAcentos(nombre)).find();
    }

    private static boolean esUnDescuento(String nombre) {
        return ES_UN_DESCUENTO.matcher(sinAcentos(nombre)).find();
    }

    private static Control control(List<Linea> pie, BigDecimal suma, List<Recargo> recargos) {
        BigDecimal total = recargos.stream().map(Recargo::importe).reduce(suma, BigDecimal::add);
        boolean cuadra = pie.stream().flatMap(linea -> linea.palabras().stream()).filter(Palabra::esNumero)
            .anyMatch(palabra -> coinciden(total, palabra.valor()));
        return cuadra ? Control.CUADRA : Control.SIN_CONTROL;
    }

    // ---- encabezado ----

    private static Encabezado encabezado(List<Linea> lineas) {
        Integer puntoDeVenta = null;
        Long numero = null;
        LocalDate fecha = null;
        String letra = null;
        Set<String> cuits = new LinkedHashSet<>();
        for (Linea linea : lineas) {
            Matcher comprobante = PUNTO_DE_VENTA_Y_NUMERO.matcher(linea.texto());
            if (numero == null && comprobante.find()) {
                puntoDeVenta = Integer.valueOf(comprobante.group(1));
                numero = Long.valueOf(comprobante.group(2));
            }
            Matcher enLinea = PUNTO_DE_VENTA_Y_NUMERO_ARCA.matcher(linea.texto());
            if (numero == null && enLinea.find()) {
                puntoDeVenta = Integer.valueOf(enLinea.group(1));
                numero = Long.valueOf(enLinea.group(2));
            }
            if (fecha == null) {
                fecha = primeraFecha(linea.texto());
            }
            Matcher cuit = CUIT.matcher(linea.texto());
            while (cuit.find()) {
                String digitos = cuit.group().replace("-", "");
                if (cuitValido(digitos)) {
                    cuits.add(digitos);
                }
            }
        }
        letra = letraPorCodigo(lineas);
        if (letra == null) {
            letra = letraSuelta(lineas);
        }
        return new Encabezado(letra, puntoDeVenta, numero, fecha, List.copyOf(cuits));
    }

    /** CbteTipo de ARCA: 1 = A, 6 = B, 11 = C. */
    private static String letraPorCodigo(List<Linea> lineas) {
        for (Linea linea : lineas) {
            Matcher codigo = CODIGO_DE_COMPROBANTE.matcher(linea.texto());
            while (codigo.find()) {
                switch (codigo.group(1)) {
                    case "01" -> {
                        return "A";
                    }
                    case "06" -> {
                        return "B";
                    }
                    case "11" -> {
                        return "C";
                    }
                    default -> {
                    }
                }
            }
        }
        return null;
    }

    private static String letraSuelta(List<Linea> lineas) {
        for (Linea linea : lineas) {
            for (Palabra palabra : linea.palabras()) {
                if (palabra.texto().equals("A") || palabra.texto().equals("B") || palabra.texto().equals("C")) {
                    return palabra.texto();
                }
            }
        }
        return null;
    }

    /**
     * La primera fecha de la linea que no viene justo despues de una etiqueta de inicio, actividad o vencimiento: se
     * mira el texto entre la fecha anterior (o el comienzo de la linea) y esta.
     */
    private static LocalDate primeraFecha(String texto) {
        Matcher fecha = FECHA.matcher(texto);
        int desde = 0;
        while (fecha.find()) {
            String antes = texto.substring(desde, fecha.start());
            desde = fecha.end();
            if (NO_ES_LA_FECHA.matcher(antes).find()) {
                continue;
            }
            try {
                return LocalDate.of(Integer.parseInt(fecha.group(3)), Integer.parseInt(fecha.group(2)),
                    Integer.parseInt(fecha.group(1)));
            } catch (DateTimeException e) {
                // Un dd/mm/aaaa que no es una fecha: se sigue con el siguiente.
            }
        }
        return null;
    }

    private static boolean cuitValido(String digitos) {
        int suma = 0;
        for (int i = 0; i < PESOS_DEL_CUIT.length; i++) {
            suma += (digitos.charAt(i) - '0') * PESOS_DEL_CUIT[i];
        }
        int verificador = 11 - suma % 11;
        if (verificador == 11) {
            verificador = 0;
        }
        return verificador != 10 && verificador == digitos.charAt(10) - '0';
    }
}
