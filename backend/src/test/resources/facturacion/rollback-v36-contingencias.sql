-- Rollback de las contingencias de facturacion: saca la V36 (en comprobante_fiscal, la marca de provisorio, SIN_FACTURA con
-- su motivo, usuario y hora, el reemplazo y sus indices, y vuelven el unico por venta y los CHECK de estado y de coherencia
-- de V32; en serie_fiscal, el backoff). Lo demas de las dos tablas queda como estaba.
-- SOLO sin historia, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola de
-- mysql o con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde de
-- rollback-v35 y rollback-v31: un ALTER TABLE no se encadena a una tabla guarda).
--   1. La guarda de comprobante_fiscal: un CHECK que exige que no haya ningun SIN_FACTURA ni ningun reemplazado. MySQL lo
--      valida contra las filas que hay, asi que con uno solo falla (ck_rollback_v36_sin_historia) y no queda puesto.
--      Mientras esta puesto, ningun registro puede pasar a SIN_FACTURA ni quedar reemplazado: si quedo de una corrida
--      cortada, sigue valiendo.
--   2. Un solo ALTER TABLE que suelta la guarda y deshace todo lo de comprobante_fiscal. Es atomico y, si la guarda no
--      existe, falla sin tocar nada: sin guarda no se borra ninguna columna. Sin reemplazados hay a lo sumo un registro por
--      venta, asi que el unico de V32 vuelve a entrar.
--   3. serie_fiscal, solo si el paso 2 corrio: la sentencia sale de information_schema (si comprobante_fiscal todavia tiene
--      alguna columna de la V36, es un SELECT de una columna que no existe, guarda_rollback_v36_registro_sin_sacar, y no se
--      puede preparar) y se ejecuta preparada. El ALTER es atomico.
--   4. El DELETE del historial borra la 36 solo si las siete columnas ya no existen.
-- Con historia se conserva todo: un SIN_FACTURA es la decision del Dueño (definitiva, S12) y un reemplazado es lo que se le
-- mando a ARCA antes del reintento; sacarlos dejaria ventas sin factura como rechazadas, o varias facturas vigentes por
-- venta. La marca de provisorio y el backoff no frenan el rollback: el codigo anterior no los conoce (se pierde el aviso de
-- «Reimprimir factura» de las que ya tenian la marca, y el job no existe).
-- En prod, revertir el codigo solo no rompe mientras no haya historia (ddl-auto=validate no se queja de columnas de mas; la
-- marca y el backoff tienen default). Con un SIN_FACTURA o un reemplazado, el codigo anterior no conoce el estado o lee
-- varios registros por venta: la vuelta atras deja de ser posible. Este script se corre junto con la vuelta atras del
-- codigo y solo sin historia.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
-- Desde una consola, con la conexion en utf8mb4 (mysql --default-character-set=utf8mb4): el texto de los CHECK que vuelven
-- se guarda con el juego de caracteres de la conexion.
ALTER TABLE comprobante_fiscal ADD CONSTRAINT ck_rollback_v36_sin_historia
    CHECK (estado <> 'SIN_FACTURA' AND reemplazado_en IS NULL);

ALTER TABLE comprobante_fiscal
    DROP CHECK ck_rollback_v36_sin_historia,
    DROP CHECK ck_comprobante_fiscal_sin_factura,
    DROP CHECK ck_comprobante_fiscal_reemplazado,
    DROP CHECK ck_comprobante_fiscal_estado,
    ADD CONSTRAINT ck_comprobante_fiscal_estado CHECK (estado IN ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION', 'AUTORIZADO',
        'RECHAZADO', 'FUERA_DE_FECHA', 'CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA')),
    DROP CHECK ck_comprobante_fiscal_coherencia,
    ADD CONSTRAINT ck_comprobante_fiscal_coherencia CHECK (
        (numero IS NULL) = (cbte_fch IS NULL)
        AND (numero IS NULL) = (estado IN ('PENDIENTE', 'RECHAZADO', 'FUERA_DE_FECHA'))
        AND (cae IS NOT NULL) = (estado = 'AUTORIZADO')
        AND (cae IS NULL) = (cae_vencimiento IS NULL)
        AND resultado <=> CASE estado WHEN 'AUTORIZADO' THEN 'A' WHEN 'RECHAZADO' THEN 'R' END),
    DROP INDEX uk_comprobante_fiscal_vigente,
    ADD CONSTRAINT uk_comprobante_fiscal_comprobante UNIQUE (ferreteria_id, comprobante_id),
    DROP INDEX ix_comprobante_fiscal_comprobante,
    DROP INDEX ix_comprobante_fiscal_estado,
    DROP FOREIGN KEY fk_comprobante_fiscal_usuario_sin_factura,
    DROP INDEX ix_comprobante_fiscal_usuario_sin_factura,
    DROP COLUMN ticket_provisorio,
    DROP COLUMN motivo_sin_factura,
    DROP COLUMN usuario_sin_factura_id,
    DROP COLUMN sin_factura_en,
    DROP COLUMN reemplazado_en;

SET @rollback_v36_serie = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE serie_fiscal DROP CHECK ck_serie_fiscal_backoff, DROP COLUMN fallas_seguidas, DROP COLUMN proximo_reintento',
    'SELECT guarda_rollback_v36_registro_sin_sacar')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'comprobante_fiscal'
        AND column_name IN ('ticket_provisorio', 'motivo_sin_factura', 'usuario_sin_factura_id', 'sin_factura_en',
            'reemplazado_en'));

PREPARE rollback_v36_serie FROM @rollback_v36_serie;

EXECUTE rollback_v36_serie;

DEALLOCATE PREPARE rollback_v36_serie;

DELETE FROM flyway_schema_history WHERE version = '36' AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND (
        (table_name = 'comprobante_fiscal' AND column_name IN ('ticket_provisorio', 'motivo_sin_factura',
            'usuario_sin_factura_id', 'sin_factura_en', 'reemplazado_en'))
        OR (table_name = 'serie_fiscal' AND column_name IN ('fallas_seguidas', 'proximo_reintento'))));
