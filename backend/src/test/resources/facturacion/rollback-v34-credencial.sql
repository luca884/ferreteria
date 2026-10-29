-- Rollback de la credencial de ARCA: saca la V34 (credencial_arca y ca_simulador). Ninguna tabla anterior cambio, asi que no
-- hay nada que restaurar.
-- SOLO sin credenciales, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola de
-- mysql o con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde de
-- rollback-v33).
--   0. Suelta una guarda vieja, si quedo de una corrida cortada, para que no habilite el DROP.
--   1. La guarda: un CREATE TABLE ... SELECT con un CHECK que exige cero filas en las dos tablas. Si falla, MySQL no deja la
--      tabla creada: con una sola fila viola ck_rollback_sin_credenciales y guarda_rollback_v34 no existe.
--   2. Un solo DROP TABLE que nombra la guarda y las dos tablas (la credencial antes que la CA, que es su padre). En MySQL 8
--      es atomico y, si una de las tablas nombradas no existe, falla sin borrar ninguna: sin guarda no se borra nada.
--   3. El DELETE del historial borra la 34 solo si las dos tablas ya no existen.
-- Con datos se conserva todo: una credencial (pendiente o activa) es la clave de la ferreteria ante ARCA, que no se puede
-- volver a generar igual, y la CA del simulador firmo las credenciales del simulador.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de tablas de mas). Este script se corre junto
-- con la vuelta atras del codigo y solo si no hay datos; va antes que rollback-v33-simulador.sql, si se corren los dos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
DROP TABLE IF EXISTS guarda_rollback_v34;

CREATE TABLE guarda_rollback_v34 (
    filas BIGINT NOT NULL,
    CONSTRAINT ck_rollback_sin_credenciales CHECK (filas = 0)
) SELECT (SELECT COUNT(*) FROM credencial_arca) + (SELECT COUNT(*) FROM ca_simulador) AS filas;

DROP TABLE guarda_rollback_v34, credencial_arca, ca_simulador;

DELETE FROM flyway_schema_history WHERE version = '34' AND NOT EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
        ('credencial_arca', 'ca_simulador'));
