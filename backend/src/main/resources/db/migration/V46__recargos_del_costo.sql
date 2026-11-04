-- CU-13 (factura en PDF): recargos con nombre sobre el costo del articulo.
-- articulo.recargos_costo: lista de recargos {nombre, porcentaje} (hasta 5) que se suman sobre el costo antes del
-- margen y el IVA: costo real = costo x (1 + suma de los % / 100). Va en la misma fila del articulo (JSON), como
-- ajustes_precio de la V24.
-- compra.recargos: los recargos que traia la compra, con el mismo formato; la compra los copia a cada articulo.
-- Aditiva: los articulos y compras existentes quedan sin recargos, con el mismo precio.
ALTER TABLE articulo ADD COLUMN recargos_costo JSON NOT NULL DEFAULT (JSON_ARRAY());
ALTER TABLE compra ADD COLUMN recargos JSON NOT NULL DEFAULT (JSON_ARRAY());

-- Rollback: backend/src/test/resources/catalogo/rollback-v46-recargos-del-costo.sql
