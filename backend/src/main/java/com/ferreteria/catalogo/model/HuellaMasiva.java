package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Huella de una actualizacion masiva: SHA-256 de la operacion, el filtro y el estado de cada articulo
 * del alcance, antes y despues. La vista previa la devuelve y aplicar la recalcula; si difiere, los
 * articulos cambiaron y se responde 409.
 *
 * <p>Formato v2 (v1 no llevaba la importacion: una huella v1 pendiente da 409 al aplicar), una linea por registro terminada en salto de linea y codificada en UTF-8. Los
 * articulos van en el orden en que se agregan (el planificador los ordena por id):
 * <pre>
 * v2
 * op|COSTO_PORCENTAJE|10.00
 * filtro|proveedorId o ~|rubroId o ~|importacionId o ~|incluirInactivos|largo:q (o ~ si no hay q)
 * a|id|activo|costoAnt|margenPropioAnt o ~|precioAnt|costoNuevo|margenPropioNuevo o ~|precioNuevo
 * a|id|activo|costoAnt|margenPropioAnt o ~|precioAnt|E
 * </pre>
 * Los decimales van a escala 2 sin redondear: un valor con mas decimales lanza, para que un error
 * de programacion no se oculte hasheando distinto. La q lleva su largo para que un {@code |} dentro
 * del texto no se confunda con un separador.
 *
 * <p>Solo con {@code CAMBIAR_RUBRO} (sufijos opcionales, asi los otros modos hashean igual que antes): la linea op
 * lleva {@code ~} como valor y el rubro destino ({@code op|CAMBIAR_RUBRO|~|destinoId}), y cada articulo lleva su
 * rubro de hoy despues del precio anterior ({@code |rubro:id} o {@code |rubro:~}). Si alguien cambia el rubro de un
 * articulo entre la vista previa y aplicar, la huella difiere y aplicar da 409.
 */
final class HuellaMasiva {

    private static final String SIN_VALOR = "~";

    private final MessageDigest digest;
    /** Si cada articulo lleva su rubro de hoy: solo al cambiar el rubro. */
    private final boolean conRubro;

    HuellaMasiva(OperacionMasiva operacion, FiltroArticulos filtro) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible.", e);
        }
        this.conRubro = operacion.modo() == ModoMasivo.CAMBIAR_RUBRO;
        linea("v2");
        linea("op|" + operacion.modo() + "|" + opcional(operacion.valor())
            + (conRubro ? "|" + id(operacion.rubroDestinoId()) : ""));
        linea("filtro|" + id(filtro.proveedorId()) + "|" + id(filtro.rubroId()) + "|" + id(filtro.importacionId())
            + "|" + filtro.incluirInactivos()
            + "|" + texto(filtro.q()));
    }

    /** Un articulo sin error, con los valores que tendria despues de la operacion. */
    void agregar(ArticuloVigente articulo, BigDecimal costoNuevo, BigDecimal margenPropioNuevo, BigDecimal precioNuevo) {
        linea(anterior(articulo) + "|" + decimal(costoNuevo) + "|" + opcional(margenPropioNuevo) + "|"
            + decimal(precioNuevo));
    }

    /** Un articulo con error: no cambia, pero su estado y su pertenencia al alcance cuentan. */
    void agregarError(ArticuloVigente articulo) {
        linea(anterior(articulo) + "|E");
    }

    /** El hash en 64 caracteres hexadecimales en minuscula. Cierra la huella: llamarlo una sola vez. */
    String calcular() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private String anterior(ArticuloVigente a) {
        return "a|" + a.id() + "|" + a.activo() + "|" + decimal(a.costo()) + "|" + opcional(a.margenPorcentaje())
            + "|" + decimal(a.precioFinal()) + rubro(a) + precioManualYAjustes(a);
    }

    /** {@code CAMBIAR_RUBRO}: el rubro de hoy del articulo; en los otros modos nada, como antes. */
    private String rubro(ArticuloVigente a) {
        return conRubro ? "|rubro:" + id(a.rubroId()) : "";
    }

    /**
     * CU-14: solo si el articulo tiene precio manual o ajustes sobre el precio final, asi la huella de un articulo sin
     * ninguno de los dos es la misma que antes (no invalida vistas previas abiertas durante un deploy).
     */
    private static String precioManualYAjustes(ArticuloVigente a) {
        if (!a.precioManual() && a.ajustesPrecio().estaVacia()) {
            return "";
        }
        return "|" + a.precioManual() + "|" + ajustesPrecio(a.ajustesPrecio());
    }

    /** Los ajustes sobre el precio final en orden (CU-14): tipo:valor:nombre, separados por punto y coma. */
    private static String ajustesPrecio(AjustesDePrecio ajustes) {
        StringBuilder texto = new StringBuilder();
        for (AjustePrecio ajuste : ajustes.lista()) {
            texto.append(ajuste.tipo()).append(':').append(decimal(ajuste.valor())).append(':')
                .append(ajuste.nombre() == null ? "" : ajuste.nombre()).append(';');
        }
        return texto.toString();
    }

    private void linea(String texto) {
        digest.update((texto + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String decimal(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String opcional(BigDecimal valor) {
        return valor == null ? SIN_VALOR : decimal(valor);
    }

    private static String id(Long valor) {
        return valor == null ? SIN_VALOR : valor.toString();
    }

    private static String texto(String q) {
        return q == null ? SIN_VALOR : q.length() + ":" + q;
    }
}
