package com.ferreteria.menu.dto;

import java.util.List;

/** El cuerpo de un PUT y el contenido JSON de una disposición guardada. */
public record Disposicion(List<Grupo> grupos, List<String> ocultas) {
    public record Grupo(String id, String rotulo, String tono, List<String> claves) {}
}
