package com.ferreteria.catalogo.model;

/**
 * Que articulos de una ferreteria se buscan: el listado (CU-03) y la actualizacion masiva (CU-05)
 * usan este mismo criterio. Los filtros se combinan con AND y los que son null no filtran.
 *
 * @param proveedorId       solo los de este proveedor; null no filtra
 * @param rubroId           solo los de este rubro; null no filtra
 * @param q                 texto a buscar en descripcion y codigos; null si estaba vacio o en blanco, sin espacios sobrantes
 * @param incluirInactivos  true trae tambien los dados de baja
 * @param importacionId     solo los articulos cuya ultima importacion es esta; null no filtra
 */
public record FiltroArticulos(Long proveedorId, Long rubroId, String q, boolean incluirInactivos, Long importacionId) {

    public FiltroArticulos {
        q = q == null || q.isBlank() ? null : q.trim();
    }

    /** Sin filtro por importacion. */
    public FiltroArticulos(Long proveedorId, Long rubroId, String q, boolean incluirInactivos) {
        this(proveedorId, rubroId, q, incluirInactivos, null);
    }

    /** El patron LIKE de {@link #q}, o null si no hay texto. */
    public String patron() {
        return patronDeBusqueda(q);
    }

    /** True si no filtra por proveedor, rubro, importacion ni texto: alcanza a todo el catalogo. Los inactivos no cuentan. */
    public boolean vacio() {
        return proveedorId == null && rubroId == null && q == null && importacionId == null;
    }

    /** null si q esta vacia; si no, %q% con \, % y _ escapados para que se busquen como texto. */
    public static String patronDeBusqueda(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escapado = q.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escapado + "%";
    }
}
