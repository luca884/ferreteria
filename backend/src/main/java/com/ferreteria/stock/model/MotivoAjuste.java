package com.ferreteria.stock.model;

/**
 * Por que se ajusta el stock a mano (CU-09). El movimiento guarda el motivo como texto legible: el nombre y,
 * si lo hay, el detalle que escribio el Dueno ("Rotura: se cayo la caja"). OTRO exige el detalle.
 */
public enum MotivoAjuste {
    INGRESO_MERCADERIA("Ingreso de mercadería"),
    ROTURA("Rotura"),
    CONTEO("Conteo"),
    OTRO("Otro");

    public static final int DETALLE_MAXIMO = 150;

    private final String nombre;

    MotivoAjuste(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }

    /** Texto del movimiento. Lanza IllegalArgumentException (con el mensaje al usuario) si el detalle no sirve. */
    public String describir(String detalle) {
        String limpio = detalle == null ? "" : detalle.trim();
        if (limpio.length() > DETALLE_MAXIMO) {
            throw new IllegalArgumentException("El detalle del motivo no puede superar 150 caracteres.");
        }
        if (limpio.isEmpty()) {
            if (this == OTRO) {
                throw new IllegalArgumentException("Contá el motivo del ajuste.");
            }
            return nombre;
        }
        return nombre + ": " + limpio;
    }
}
