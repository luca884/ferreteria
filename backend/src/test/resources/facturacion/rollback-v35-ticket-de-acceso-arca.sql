-- Rollback del ticket de acceso de ARCA: saca la V35 (ticket_acceso, ticket_simulado y el bloqueo de config_arca:
-- bloqueo_codigo, bloqueo_mensaje, bloqueado_en y ck_config_arca_bloqueo). Lo demas de config_arca queda como estaba.
-- SOLO sin bloqueos, y seguro aunque se corra sentencia por sentencia sin cortar ante un error (pegado en una consola de
-- mysql o con mysql --force): cada paso que borra depende del anterior, no de que el cliente se detenga (molde de
-- rollback-v34 para las tablas, y de rollback-v31 para las columnas, porque un ALTER TABLE no se encadena a una tabla guarda).
--   1. La guarda de las columnas: un CHECK en config_arca que exige el bloqueo vacio. MySQL lo valida contra las filas que
--      hay, asi que con un solo bloqueo falla (ck_rollback_sin_bloqueos) y no queda puesto. Mientras esta puesto, ninguna
--      fila puede bloquearse: si quedo de una corrida cortada, sigue valiendo.
--   2. Un solo ALTER TABLE que suelta la guarda, el CHECK del bloqueo y las tres columnas. Es atomico y, si la guarda no
--      existe, falla sin tocar nada: sin guarda no se borra ninguna columna.
--   3. Suelta una guarda vieja de las tablas, si quedo de una corrida cortada, para que no habilite el DROP.
--   4. La guarda de las tablas: un CREATE TABLE ... SELECT con un CHECK que exige que las columnas del bloqueo ya no esten
--      (el paso 2 corrio). Si falla, MySQL no deja la tabla creada: con las columnas puestas viola
--      ck_rollback_bloqueo_ya_sacado y guarda_rollback_v35 no existe.
--   5. Un solo DROP TABLE que nombra la guarda y las dos tablas. En MySQL 8 es atomico y, si una de las tablas nombradas no
--      existe, falla sin borrar ninguna: sin guarda no se borra nada.
--   6. El DELETE del historial borra la 35 solo si las dos tablas y las tres columnas ya no existen.
-- Con un bloqueo se conserva todo: es el rechazo de ARCA al certificado de la ferreteria (C4), que frena su facturacion
-- hasta que el Dueño cambie el certificado; sacarlo la dejaria facturar con un certificado que ARCA no acepta. Los tickets
-- no frenan el rollback: el TA se vuelve a pedir con loginCms y el simulador emite otro.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de tablas ni de columnas de mas, y las
-- columnas son nullable). Este script se corre junto con la vuelta atras del codigo y solo si no hay bloqueos; va antes que
-- rollback-v34-credencial.sql, si se corren los dos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE config_arca ADD CONSTRAINT ck_rollback_sin_bloqueos CHECK (bloqueo_codigo IS NULL);

ALTER TABLE config_arca
    DROP CHECK ck_rollback_sin_bloqueos,
    DROP CHECK ck_config_arca_bloqueo,
    DROP COLUMN bloqueo_codigo,
    DROP COLUMN bloqueo_mensaje,
    DROP COLUMN bloqueado_en;

DROP TABLE IF EXISTS guarda_rollback_v35;

CREATE TABLE guarda_rollback_v35 (
    columnas BIGINT NOT NULL,
    CONSTRAINT ck_rollback_bloqueo_ya_sacado CHECK (columnas = 0)
) SELECT COUNT(*) AS columnas FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'config_arca' AND column_name IN ('bloqueo_codigo', 'bloqueo_mensaje', 'bloqueado_en');

DROP TABLE guarda_rollback_v35, ticket_acceso, ticket_simulado;

DELETE FROM flyway_schema_history WHERE version = '35' AND NOT EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
        ('ticket_acceso', 'ticket_simulado')) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'config_arca'
        AND column_name IN ('bloqueo_codigo', 'bloqueo_mensaje', 'bloqueado_en'));
