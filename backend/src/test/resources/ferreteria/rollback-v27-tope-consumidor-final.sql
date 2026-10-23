-- Rollback del tope de Consumidor Final: saca la V27. Se pierde el tope que haya cargado cada Dueño.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de columnas de mas y la columna tiene
-- valor por defecto); correr este script junto con la vuelta atras del codigo.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE ferreteria DROP CHECK ck_ferreteria_tope_consumidor_final, DROP COLUMN tope_consumidor_final;
DELETE FROM flyway_schema_history WHERE version = '27';
