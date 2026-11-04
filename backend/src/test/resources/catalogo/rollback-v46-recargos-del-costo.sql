-- Rollback de CU-13 (recargos del costo): saca la V46.
-- Se pierden los recargos de cada articulo y de cada compra; el costo y el precio final quedan como estaban.
-- En prod, revertir el codigo alcanza: V46 queda como "future" y el codigo viejo no mapea las columnas
-- (ddl-auto=validate no se queja de columnas de mas). Con eso el proximo recalculo del precio ya no usa el costo real.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE compra DROP COLUMN recargos;
ALTER TABLE articulo DROP COLUMN recargos_costo;
DELETE FROM flyway_schema_history WHERE version = '46';
