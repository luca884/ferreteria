-- Rollback del corte 3 (listas-y-moneda, lote 5b): saca la V8.
-- En prod, revertir el codigo alcanza: V8 queda como "future" (la ultima migracion aplicada) y Flyway la ignora, la
-- columna queda con su valor y el codigo viejo no la mapea (ddl-auto=validate no se queja de columnas de mas).
-- La version deja de chequearse, pero no se pierde ningun dato.
-- En dev/test, con los datos demo (V1000+, out-of-order), una V8 aplicada sin archivo local es "missing" y la
-- validacion falla al levantar el codigo anterior: hay que correr este script ANTES de levantarlo.
ALTER TABLE articulo DROP COLUMN version;
DELETE FROM flyway_schema_history WHERE version = '8';
