package com.ferreteria.menu.dto;

public record MenuResponse(Origen origen, Disposicion disposicion) {
    public enum Origen { USUARIO, ROL, ORIGINAL }

    public static MenuResponse original() {
        return new MenuResponse(Origen.ORIGINAL, null);
    }
}
