-- Rollback del comprobante asociado de la nota de credito fiscal: saca la V38 (en comprobante_fiscal, las seis columnas del
-- asociado, su FK, su indice y su CHECK). Lo demas de la tabla queda como estaba.
-- SOLO sin notas de credito, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una
-- consola de mysql o con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde
-- de rollback-v36):
--   1. La guarda: un CHECK que exige que no haya ningun registro NOTA_CREDITO. MySQL lo valida contra las filas que hay,
--      asi que con uno solo falla (ck_rollback_v38_sin_notas) y no queda puesto. Mientras esta puesto, no entra ninguna
--      NC: si quedo de una corrida cortada, sigue valiendo.
--   2. Un solo ALTER TABLE que suelta la guarda y deshace todo lo de la V38. Es atomico y, si la guarda no existe, falla
--      sin tocar nada: sin guarda no se borra ninguna columna.
--   3. El DELETE del historial borra la 38 solo si las seis columnas ya no existen.
-- Con una NC se conserva todo: es lo que se le mando a ARCA (o se le va a mandar) para anular una factura, y sin su
-- asociado no se puede reenviar ni imprimir. Sacarla dejaria la venta anulada sin su comprobante fiscal.
-- En prod, revertir el codigo solo no rompe mientras no haya notas de credito (ddl-auto=validate no se queja de columnas de
-- mas, y las facturas las tienen en NULL). Con una NC, el codigo anterior la mandaria sin CbtesAsoc: la vuelta atras deja
-- de ser posible. Este script se corre junto con la vuelta atras del codigo y solo sin notas de credito.
-- Desde una consola, con la conexion en utf8mb4 (mysql --default-character-set=utf8mb4).
ALTER TABLE comprobante_fiscal ADD CONSTRAINT ck_rollback_v38_sin_notas CHECK (clase <> 'NOTA_CREDITO');

ALTER TABLE comprobante_fiscal
    DROP CHECK ck_rollback_v38_sin_notas,
    DROP CHECK ck_comprobante_fiscal_asociado,
    DROP FOREIGN KEY fk_comprobante_fiscal_asociado,
    DROP INDEX ix_comprobante_fiscal_asociado,
    DROP COLUMN asociado_id,
    DROP COLUMN asociado_cbte_tipo,
    DROP COLUMN asociado_punto_venta,
    DROP COLUMN asociado_numero,
    DROP COLUMN asociado_cuit,
    DROP COLUMN asociado_cbte_fch;

DELETE FROM flyway_schema_history WHERE version = '38' AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'comprobante_fiscal'
        AND column_name IN ('asociado_id', 'asociado_cbte_tipo', 'asociado_punto_venta', 'asociado_numero',
            'asociado_cuit', 'asociado_cbte_fch'));
