-- Rollback de la numeracion de ajustes por tipo: saca la V40 y vuelve al numerador unico por ferreteria de la V28.
-- SOLO sin dos ajustes de la misma ferreteria con el mismo numero (un Recargo N.º 1 y un Descuento N.º 1): el codigo anterior
-- los numera con un solo numerador y no podria seguir.
--   1. Un solo ALTER sobre ajuste_cuenta que vuelve a poner el unico (ferreteria, numero) y suelta el de la V40. Es la guarda:
--      con un numero repetido falla (uk_ajuste_cuenta_numero) y no toca nada, y el script se corta ahi.
--   2. El numerador se vacia, pierde el tipo y vuelve a ser unico por ferreteria; despues se llena desde ajuste_cuenta con el
--      numero mas alto de la ferreteria, sin importar el tipo. Si el script se cortara entre el vaciado y el llenado, el
--      proximo ajuste chocaria con uk_ajuste_cuenta_numero (no se repite ningun numero): se vuelve a correr desde el paso 2.
--   3. El DELETE del historial borra la 40 solo si la columna tipo ya no existe.
-- Se corre junto con la vuelta atras del codigo, con la aplicacion parada.
ALTER TABLE ajuste_cuenta
    ADD CONSTRAINT uk_ajuste_cuenta_numero UNIQUE (ferreteria_id, numero),
    DROP INDEX uk_ajuste_cuenta_tipo_numero;

DELETE FROM numerador_ajuste_cuenta;

ALTER TABLE numerador_ajuste_cuenta
    ADD CONSTRAINT uk_numerador_ajuste_cuenta_ferreteria UNIQUE (ferreteria_id),
    DROP CHECK ck_numerador_ajuste_cuenta_tipo,
    DROP INDEX uk_numerador_ajuste_cuenta_ferreteria_tipo,
    DROP COLUMN tipo;

INSERT INTO numerador_ajuste_cuenta (ferreteria_id, ultimo_numero)
SELECT ferreteria_id, MAX(numero)
FROM ajuste_cuenta
GROUP BY ferreteria_id;

DELETE FROM flyway_schema_history WHERE version = '40' AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'numerador_ajuste_cuenta'
        AND column_name = 'tipo');
