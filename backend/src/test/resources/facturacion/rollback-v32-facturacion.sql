-- Rollback de las tablas de facturacion: saca la V32 (config_arca, serie_fiscal, comprobante_fiscal,
-- comprobante_fiscal_alicuota e intento_fiscal). Ninguna tabla anterior cambio, asi que no hay nada que restaurar.
-- SOLO sin datos, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola de mysql o
-- con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga.
--   0. Suelta una guarda vieja, si quedo de una corrida cortada, para que no habilite el DROP.
--   1. La guarda: un CREATE TABLE ... SELECT con un CHECK que exige cero filas en las cinco tablas. Si falla, MySQL no deja la
--      tabla creada: con una sola fila viola ck_rollback_sin_datos_de_facturacion y guarda_rollback_v32 no existe.
--   2. Un solo DROP TABLE que nombra la guarda y las cinco tablas. En MySQL 8 es atomico y, si una de las tablas nombradas no
--      existe, falla sin borrar ninguna: sin guarda no se borra nada.
--   3. El DELETE del historial borra la 32 solo si las cinco tablas ya no existen.
-- Con datos se conserva todo: un registro fiscal o un intento son la huella de lo que se mando a ARCA, y despues del primer
-- CAE (simulado o real) la vuelta atras es solo de codigo.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de tablas de mas). Este script se corre junto
-- con la vuelta atras del codigo y solo si no hay datos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
DROP TABLE IF EXISTS guarda_rollback_v32;

CREATE TABLE guarda_rollback_v32 (
    filas BIGINT NOT NULL,
    CONSTRAINT ck_rollback_sin_datos_de_facturacion CHECK (filas = 0)
) SELECT (SELECT COUNT(*) FROM config_arca) + (SELECT COUNT(*) FROM serie_fiscal) + (SELECT COUNT(*) FROM comprobante_fiscal)
       + (SELECT COUNT(*) FROM comprobante_fiscal_alicuota) + (SELECT COUNT(*) FROM intento_fiscal) AS filas;

DROP TABLE guarda_rollback_v32, intento_fiscal, comprobante_fiscal_alicuota, comprobante_fiscal, serie_fiscal, config_arca;

DELETE FROM flyway_schema_history WHERE version = '32' AND NOT EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
        ('config_arca', 'serie_fiscal', 'comprobante_fiscal', 'comprobante_fiscal_alicuota', 'intento_fiscal'));
