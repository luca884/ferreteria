-- Rollback del corte 2 (listas-y-moneda): sacar de proveedor.mapeo_lista las claves que agrego V6 ANTES de volver al
-- codigo anterior. El MapeoLista viejo no ignora las propiedades desconocidas y Hibernate usa su propio ObjectMapper,
-- que falla con ellas: sin esta limpieza el proveedor no se podria leer.
-- La V6 no se revierte: sus columnas son NULL o tienen DEFAULT y el codigo viejo las ignora.
-- Ademas de las claves de primer nivel (design, seccion 7) se sacan las de "encabezados", que tambien las lee el
-- codigo viejo (record Encabezados de 3 textos).
UPDATE proveedor
SET mapeo_lista = JSON_REMOVE(mapeo_lista,
    '$.hojas', '$.columnaMoneda', '$.monedaFija', '$.columnaSinIva', '$.columnaConIva', '$.columnaIva',
    '$.columnaAjuste1', '$.columnaAjuste2', '$.columnaAjuste3', '$.columnaCodigoBarras', '$.columnaUnidad',
    '$.columnaRubro',
    '$.encabezados.moneda', '$.encabezados.sinIva', '$.encabezados.conIva', '$.encabezados.iva',
    '$.encabezados.ajuste1', '$.encabezados.ajuste2', '$.encabezados.ajuste3', '$.encabezados.codigoBarras',
    '$.encabezados.unidad', '$.encabezados.rubro')
WHERE mapeo_lista IS NOT NULL;
