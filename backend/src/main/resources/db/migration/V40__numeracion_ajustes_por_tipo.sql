-- CU-12 (cu-12-numeracion-ajustes): los Recargos y los Descuentos se numeran cada uno por su lado, como GDS (G95). Hasta la
-- V39 compartian un solo numerador por ferreteria y el primer Descuento podia salir «Descuento N.º 2».
--
-- 1. ajuste_cuenta: el unico pasa de (ferreteria, numero) a (ferreteria, tipo, numero), asi un Descuento N.º 1 convive con el
--    Recargo N.º 1. Un solo ALTER: si falla, no toca nada. fk_numerador... y las FK compuestas tienen otros indices que
--    empiezan por ferreteria_id, asi que soltar el unico viejo no deja a ninguna FK sin indice.
-- 2. numerador_ajuste_cuenta: pasa a una fila por ferreteria y tipo. Se vacia y se vuelve a llenar desde ajuste_cuenta con el
--    numero mas alto de cada tipo en cada ferreteria: los ajustes existentes conservan su numero y cada tipo sigue desde su
--    maximo (S2). ajuste_cuenta es inmutable y un ajuste revertido no consume numero, asi que el maximo de la tabla es el
--    ultimo numero dado. Una ferreteria sin ajustes queda sin numerador, como antes: el UPSERT crea la fila en 1.
--
-- Deploy: el codigo anterior escribe el numerador sin tipo y no puede registrar un ajuste despues de esta migracion; si la
-- instancia vieja recibe un ajuste mientras arranca la nueva, ese pedido da error y se reintenta. Conviene deployar cuando
-- nadie esta cargando ajustes.
-- Rollback: backend/src/test/resources/clientes/rollback-v40-numeracion-ajustes.sql (solo sin dos ajustes de la misma
-- ferreteria con el mismo numero).
ALTER TABLE ajuste_cuenta
    ADD CONSTRAINT uk_ajuste_cuenta_tipo_numero UNIQUE (ferreteria_id, tipo, numero),
    DROP INDEX uk_ajuste_cuenta_numero;

DELETE FROM numerador_ajuste_cuenta;

ALTER TABLE numerador_ajuste_cuenta
    ADD COLUMN tipo VARCHAR(10) NOT NULL AFTER ferreteria_id,
    ADD CONSTRAINT uk_numerador_ajuste_cuenta_ferreteria_tipo UNIQUE (ferreteria_id, tipo),
    DROP INDEX uk_numerador_ajuste_cuenta_ferreteria,
    ADD CONSTRAINT ck_numerador_ajuste_cuenta_tipo CHECK (tipo IN ('RECARGO', 'DESCUENTO'));

INSERT INTO numerador_ajuste_cuenta (ferreteria_id, tipo, ultimo_numero)
SELECT ferreteria_id, tipo, MAX(numero)
FROM ajuste_cuenta
GROUP BY ferreteria_id, tipo;
