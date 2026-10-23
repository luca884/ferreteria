-- Rollback de CU-07 (tope de descuento): saca la V25. Se pierden los topes cargados.
-- En prod, revertir el codigo alcanza: V25 queda como "future" y el codigo viejo no mapea la columna
-- (ddl-auto=validate no se queja de columnas de mas).
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE usuario DROP CHECK ck_usuario_tope_descuento, DROP COLUMN tope_descuento;
DELETE FROM flyway_schema_history WHERE version = '25';
