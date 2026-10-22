-- Rollback de CU-14: saca la V24.
-- Se pierden los precios manuales (la marca; el precio final queda como estaba) y los ajustes sobre el precio final.
-- En prod, revertir el codigo alcanza: V24 queda como "future" y el codigo viejo no mapea las columnas
-- (ddl-auto=validate no se queja de columnas de mas). Con eso el proximo recalculo pisa los precios manuales.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE articulo DROP COLUMN ajustes_precio, DROP COLUMN precio_manual;
DELETE FROM flyway_schema_history WHERE version = '24';
