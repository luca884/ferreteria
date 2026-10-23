-- Rollback de los ajustes de cuenta corriente: saca la V28 (ajuste_cuenta, su numerador, ajuste_id del libro y el origen/destino
-- de ajuste de la imputacion; recibo_id y comprobante_id vuelven a ser obligatorios).
-- SOLO sin ajustes cargados. La primera sentencia es una guarda: agrega un CHECK imposible sobre ajuste_cuenta, y MySQL valida
-- las filas existentes, asi que con un solo ajuste el script se niega (ck_rollback_sin_ajustes) ANTES de tocar nada. Con ajustes
-- se conserva todo: un Recargo o un Descuento ya mueve el saldo y las imputaciones del cliente.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de columnas de mas), pero los ajustes nuevos
-- no se podrian cargar; correr este script junto con la vuelta atras del codigo y solo si no hay ajustes.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
-- Las imputaciones automaticas (credito consumido por una venta a cuenta) se quedan como filas validas (recibo -> comprobante):
-- sin la columna `automatica` el recibo impreso las listaria, pero la cuenta sigue cuadrando.
-- Los UNIQUE compuestos se sueltan a mano antes de las columnas: MySQL, al borrar una columna, la saca de los indices que la
-- incluyen y dejaria UNIQUE (recibo_id) a secas.
ALTER TABLE ajuste_cuenta ADD CONSTRAINT ck_rollback_sin_ajustes CHECK (numero < 0);

ALTER TABLE imputacion
    DROP CHECK ck_imputacion_origen,
    DROP CHECK ck_imputacion_destino,
    DROP INDEX uk_imputacion_recibo_ajuste,
    DROP INDEX uk_imputacion_ajuste_comprobante,
    DROP INDEX uk_imputacion_ajuste_ajuste,
    DROP INDEX ix_imputacion_cliente_ajuste,
    DROP FOREIGN KEY fk_imputacion_ajuste_origen,
    DROP FOREIGN KEY fk_imputacion_ajuste_destino;

ALTER TABLE imputacion
    DROP COLUMN ajuste_origen_id,
    DROP COLUMN ajuste_destino_id,
    DROP COLUMN automatica,
    MODIFY COLUMN recibo_id      BIGINT NOT NULL,
    MODIFY COLUMN comprobante_id BIGINT NOT NULL;

ALTER TABLE movimiento_cuenta_cliente
    DROP CHECK ck_mcc_ajuste_ref,
    DROP FOREIGN KEY fk_mcc_ajuste,
    DROP INDEX uk_mcc_ajuste,
    DROP COLUMN ajuste_id;

DROP TABLE ajuste_cuenta;
DROP TABLE numerador_ajuste_cuenta;
DELETE FROM flyway_schema_history WHERE version = '28';
