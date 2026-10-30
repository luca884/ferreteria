-- Rollback de la NC en la cuenta corriente: saca la V39 (en imputacion, nc_origen_id con su FK compuesta, su indice y sus
-- unicos por par, y ck_imputacion_origen_v39) y vuelve a poner ck_imputacion_origen de V28, con su nombre de siempre: el
-- rollback de V28 lo suelta por ese nombre, asi que la cadena V39 -> V28 sigue andando. Lo demas de la tabla queda como estaba.
-- SOLO sin NC en la cuenta corriente:
--   1. La guarda del libro: un CHECK que exige que no haya ningun renglon NC. MySQL lo valida contra las filas que hay, asi
--      que con uno solo falla (ck_rollback_v39_sin_nc_en_libro) y no queda puesto. Si entra, se suelta enseguida (2): no
--      queda puesto si el script se corta despues. Una NC que quedo entera a favor (la factura ya estaba cobrada) no tiene
--      imputaciones, pero su haber ya esta en el saldo: el codigo anterior no la cuenta como credito y la cuenta dejaria de
--      cuadrar. Esta guarda frena el script corrido de corrido (como lo corre MigracionV39Test); corrido sentencia por
--      sentencia sin cortar ante un error (mysql --force) NO alcanza para esa NC sin imputaciones: no hay que correrlo asi.
--   3. La guarda de la imputacion: un CHECK que exige que no haya ninguna imputacion con nc_origen_id
--      (ck_rollback_v39_sin_nc). Con una sola, falla. Mientras esta puesto no se puede imputar ninguna NC: si quedo de una
--      corrida cortada, sigue valiendo.
--   4. Un solo ALTER TABLE que suelta la guarda de la imputacion y deshace la V39. Es atomico y, si la guarda no existe,
--      falla sin tocar nada; y si quedara alguna imputacion de una NC, el CHECK de V28 que vuelve a entrar la rechaza (sin
--      recibo ni Descuento) y tampoco se toca nada.
--   5. El DELETE del historial borra la 39 solo si nc_origen_id ya no existe.
-- Los unicos se sueltan a mano antes que la columna: MySQL, al borrar una columna, la saca de los indices que la incluyen y
-- dejaria UNIQUE (comprobante_id) y UNIQUE (ajuste_destino_id) a secas.
-- En prod, revertir el codigo solo no rompe mientras no haya NC en ninguna cuenta (ddl-auto=validate no se queja de columnas
-- de mas, y nc_origen_id es nula). Con una NC en la cuenta corriente la vuelta atras deja de ser posible. Este script se corre
-- junto con la vuelta atras del codigo y solo sin NC.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE movimiento_cuenta_cliente ADD CONSTRAINT ck_rollback_v39_sin_nc_en_libro CHECK (tipo <> 'NC');

ALTER TABLE movimiento_cuenta_cliente DROP CHECK ck_rollback_v39_sin_nc_en_libro;

ALTER TABLE imputacion ADD CONSTRAINT ck_rollback_v39_sin_nc CHECK (nc_origen_id IS NULL);

ALTER TABLE imputacion
    DROP CHECK ck_rollback_v39_sin_nc,
    DROP CHECK ck_imputacion_origen_v39,
    ADD CONSTRAINT ck_imputacion_origen CHECK ((recibo_id IS NULL) <> (ajuste_origen_id IS NULL)),
    DROP INDEX uk_imputacion_nc_ajuste,
    DROP FOREIGN KEY fk_imputacion_nc_origen,
    DROP INDEX ix_imputacion_ferreteria_nc_origen,
    DROP COLUMN nc_origen_id;

DELETE FROM flyway_schema_history WHERE version = '39' AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'imputacion'
        AND column_name = 'nc_origen_id');
