-- Rollback del estado del simulador de WSFEv1: saca la V33 (simulador_wsfe_serie y simulador_wsfe_comprobante). Ninguna tabla
-- anterior cambio, asi que no hay nada que restaurar.
-- SOLO sin datos, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola de mysql o
-- con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde de rollback-v32).
--   0. Suelta una guarda vieja, si quedo de una corrida cortada, para que no habilite el DROP.
--   1. La guarda: un CREATE TABLE ... SELECT con un CHECK que exige cero filas en las dos tablas. Si falla, MySQL no deja la
--      tabla creada: con una sola fila viola ck_rollback_sin_datos_del_simulador y guarda_rollback_v33 no existe.
--   2. Un solo DROP TABLE que nombra la guarda y las dos tablas. En MySQL 8 es atomico y, si una de las tablas nombradas no
--      existe, falla sin borrar ninguna: sin guarda no se borra nada.
--   3. El DELETE del historial borra la 33 solo si las dos tablas ya no existen.
-- Con datos se conserva todo: un comprobante simulado tiene su CAE simulado, y los registros fiscales de V32 que lo adoptaron lo
-- esperan en FECompConsultar.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de tablas de mas). Este script se corre junto
-- con la vuelta atras del codigo y solo si no hay datos; va antes que rollback-v32-facturacion.sql, si se corren los dos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
DROP TABLE IF EXISTS guarda_rollback_v33;

CREATE TABLE guarda_rollback_v33 (
    filas BIGINT NOT NULL,
    CONSTRAINT ck_rollback_sin_datos_del_simulador CHECK (filas = 0)
) SELECT (SELECT COUNT(*) FROM simulador_wsfe_serie) + (SELECT COUNT(*) FROM simulador_wsfe_comprobante) AS filas;

DROP TABLE guarda_rollback_v33, simulador_wsfe_comprobante, simulador_wsfe_serie;

DELETE FROM flyway_schema_history WHERE version = '33' AND NOT EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
        ('simulador_wsfe_serie', 'simulador_wsfe_comprobante'));
