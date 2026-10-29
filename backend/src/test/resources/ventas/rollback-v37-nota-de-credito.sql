-- Rollback de la nota de credito: saca la V37 (en comprobante, la venta asociada con su FK compuesta y su unico, el motivo de
-- la anulacion interna y ck_comprobante_nota_de_credito). Lo demas de la tabla queda como estaba.
-- SOLO sin notas de credito, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola
-- de mysql o con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde de
-- rollback-v36: un ALTER TABLE no se encadena a una tabla guarda).
--   1. La guarda: un CHECK que exige que no haya ningun comprobante NOTA_CREDITO. MySQL lo valida contra las filas que hay,
--      asi que con una sola NC o anulacion interna falla (ck_rollback_v37_sin_notas) y no queda puesto. Mientras esta
--      puesto, no se puede anular ninguna venta: si quedo de una corrida cortada, sigue valiendo.
--   2. Un solo ALTER TABLE que suelta la guarda y deshace todo lo de la V37. Es atomico y, si la guarda no existe, falla sin
--      tocar nada: sin guarda no se borra ninguna columna.
--   3. El DELETE del historial borra la 37 solo si las dos columnas ya no existen.
-- Con notas se conserva todo: una NC es un documento (con CAE, o la anulacion interna que decidio el Dueño) y su venta
-- anulada; sacarlas dejaria la venta como vigente con el stock ya repuesto, y el codigo anterior no conoce el tipo
-- NOTA_CREDITO (los reportes la sumarian como una venta mas).
-- En prod, revertir el codigo solo no rompe mientras no haya notas (ddl-auto=validate no se queja de columnas de mas, y las
-- dos son nulas). Con una NOTA_CREDITO la vuelta atras deja de ser posible. Este script se corre junto con la vuelta atras
-- del codigo y solo sin notas.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE comprobante ADD CONSTRAINT ck_rollback_v37_sin_notas CHECK (tipo <> 'NOTA_CREDITO');

ALTER TABLE comprobante
    DROP CHECK ck_rollback_v37_sin_notas,
    DROP CHECK ck_comprobante_nota_de_credito,
    DROP FOREIGN KEY fk_comprobante_asociado,
    DROP INDEX uk_comprobante_asociado,
    DROP COLUMN motivo_anulacion,
    DROP COLUMN comprobante_asociado_id;

DELETE FROM flyway_schema_history WHERE version = '37' AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'comprobante'
        AND column_name IN ('comprobante_asociado_id', 'motivo_anulacion'));
