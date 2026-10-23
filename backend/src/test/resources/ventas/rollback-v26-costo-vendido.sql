-- Rollback de los reportes de ventas: saca la V26. Se pierde el costo guardado en cada renglon vendido (la ganancia de
-- esas ventas ya no se puede calcular).
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de columnas de mas y las dos aceptan
-- NULL), pero el costo de las ventas nuevas quedaria en NULL; correr este script junto con la vuelta atras del codigo.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE item_comprobante DROP CHECK ck_item_comprobante_costo, DROP COLUMN costo_unitario, DROP COLUMN costo;
DELETE FROM flyway_schema_history WHERE version = '26';
