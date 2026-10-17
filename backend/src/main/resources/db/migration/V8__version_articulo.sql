-- Ajuste (corte 3, lote 5b): bloqueo optimista del articulo. La columna version sube en 1 con cada escritura del
-- articulo (menos el stock, que se mueve con un UPDATE nativo que no la nombra).
-- Aditiva: no toca costo, precio, ajustes, base de lista, stock ni historial. Los articulos existentes quedan en 0.
-- En MySQL 8.4 es ALGORITHM=INSTANT: no reconstruye la tabla.
ALTER TABLE articulo ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Rollback: backend/src/test/resources/catalogo/rollback-v8-version-articulo.sql
