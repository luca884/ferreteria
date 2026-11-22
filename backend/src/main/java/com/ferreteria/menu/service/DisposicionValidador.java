package com.ferreteria.menu.service;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.menu.dto.Disposicion;
import com.ferreteria.menu.dto.Disposicion.Grupo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class DisposicionValidador {

    // Clave -> soloDueno. Mantener en ambos sentidos con CATALOGO de frontend/src/app/layout/menu.ts:
    // si cambia una clave o permiso allá, actualizar acá, y si cambia acá, actualizar allá.
    private static final Map<String, Boolean> PANTALLAS = Map.ofEntries(
            Map.entry("presupuestos", false), Map.entry("remitos", false), Map.entry("caja", false),
            Map.entry("clientes", false), Map.entry("ventas", true), Map.entry("reportes", true),
            Map.entry("articulos", false), Map.entry("rubros", false), Map.entry("stock-bajo-minimo", true),
            Map.entry("etiquetas", true), Map.entry("controles-de-datos", true), Map.entry("proveedores", false),
            Map.entry("compras", true), Map.entry("importaciones", true), Map.entry("actualizacion-masiva", true),
            Map.entry("margen-y-redondeo", true), Map.entry("cotizacion", true), Map.entry("formas-de-pago", true),
            Map.entry("consumidor-final", true), Map.entry("datos-de-la-ferreteria", true),
            Map.entry("facturacion", true), Map.entry("usuarios", true));
    private static final Set<String> TONOS = Set.of("ventas", "articulos", "compras", "precios", "configuracion");
    private static final Pattern ID_GRUPO = Pattern.compile("[a-z0-9-]{1,40}");

    private DisposicionValidador() {}

    public static Map<String, Boolean> pantallas() {
        return PANTALLAS;
    }

    public static Disposicion validar(Disposicion disposicion, Rol rol) {
        Set<String> usadas = new HashSet<>();
        return normalizar(disposicion, false, claves -> {
            for (String clave : claves) {
                Boolean soloDueno = PANTALLAS.get(clave);
                if (soloDueno == null) {
                    throw new BadRequestException("Hay una opción de menú que no existe.");
                }
                if (soloDueno && rol != Rol.DUENO) {
                    throw new BadRequestException("El menú de un Vendedor no puede incluir opciones de solo Dueño.");
                }
                if (!usadas.add(clave)) {
                    throw new BadRequestException("Ya pusiste esa opción en el menú.");
                }
            }
            return List.copyOf(claves);
        });
    }

    /** Una clave retirada, un cambio de rol o el id antes permitido no invalidan un ajuste guardado. */
    static Disposicion paraLectura(Disposicion disposicion, Rol rol) {
        return normalizar(disposicion, true, claves -> claves.stream().filter(clave -> {
            Boolean soloDueno = PANTALLAS.get(clave);
            return soloDueno != null && (!soloDueno || rol == Rol.DUENO);
        }).toList());
    }

    private static Disposicion normalizar(Disposicion disposicion, boolean permitirIdMostrador,
                                            UnaryOperator<List<String>> procesarClaves) {
        if (disposicion == null || disposicion.grupos() == null) {
            throw incompleta();
        }
        if (disposicion.grupos().size() > 10) {
            throw new BadRequestException("Podés tener hasta 10 grupos.");
        }

        Set<String> ids = new HashSet<>();
        Set<String> nombres = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Grupo> grupos = new ArrayList<>();
        for (Grupo grupo : disposicion.grupos()) {
            if (grupo == null) {
                throw incompleta();
            }
            String rotulo = grupo.rotulo() == null ? "" : grupo.rotulo().strip();
            if (rotulo.isEmpty()) {
                throw new BadRequestException("Poné un nombre para el grupo.");
            }
            if (rotulo.length() > 30) {
                throw new BadRequestException("El nombre del grupo puede tener hasta 30 caracteres.");
            }
            if (!nombres.add(rotulo)) {
                throw new BadRequestException("Ya hay un grupo con ese nombre.");
            }
            if (grupo.id() == null || !ID_GRUPO.matcher(grupo.id()).matches()
                    || (!permitirIdMostrador && "mostrador".equals(grupo.id()))) {
                throw new BadRequestException("El identificador del grupo no es válido.");
            }
            if (!ids.add(grupo.id())) {
                throw new BadRequestException("Ya hay un grupo con ese identificador.");
            }
            if (grupo.tono() == null || !TONOS.contains(grupo.tono())) {
                throw new BadRequestException("Elegí un color válido para el grupo.");
            }
            grupos.add(new Grupo(grupo.id(), rotulo, grupo.tono(), procesarClaves.apply(listaDeClaves(grupo.claves()))));
        }
        return new Disposicion(List.copyOf(grupos), procesarClaves.apply(listaDeClaves(disposicion.ocultas())));
    }

    private static List<String> listaDeClaves(List<String> claves) {
        if (claves == null || claves.stream().anyMatch(Objects::isNull)) {
            throw incompleta();
        }
        return claves;
    }

    private static BadRequestException incompleta() {
        return new BadRequestException("La disposición del menú no está completa.");
    }
}
