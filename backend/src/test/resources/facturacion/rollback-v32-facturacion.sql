-- Rollback de las tablas de facturacion: saca la V32 (config_arca, serie_fiscal, comprobante_fiscal,
-- comprobante_fiscal_alicuota e intento_fiscal). Ninguna tabla anterior cambio, asi que no hay nada que restaurar.
-- SOLO sin datos. La primera sentencia es una guarda: una tabla temporal con un CHECK que exige cero filas en las cinco tablas;
-- con una sola fila el INSERT viola ck_rollback_sin_datos_de_facturacion y el script se niega ANTES de tocar nada (MySQL corta
-- las sentencias que siguen). Con datos se conserva todo: un registro fiscal o un intento son la huella de lo que se mando a
-- ARCA, y despues del primer CAE (simulado o real) la vuelta atras es solo de codigo.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de tablas de mas); correr este script junto
-- con la vuelta atras del codigo y solo si no hay datos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
CREATE TEMPORARY TABLE guarda_rollback_v32 (
    filas BIGINT NOT NULL,
    CONSTRAINT ck_rollback_sin_datos_de_facturacion CHECK (filas = 0)
);
INSERT INTO guarda_rollback_v32 (filas)
SELECT (SELECT COUNT(*) FROM config_arca) + (SELECT COUNT(*) FROM serie_fiscal) + (SELECT COUNT(*) FROM comprobante_fiscal)
     + (SELECT COUNT(*) FROM comprobante_fiscal_alicuota) + (SELECT COUNT(*) FROM intento_fiscal);
DROP TEMPORARY TABLE guarda_rollback_v32;

DROP TABLE intento_fiscal;
DROP TABLE comprobante_fiscal_alicuota;
DROP TABLE comprobante_fiscal;
DROP TABLE serie_fiscal;
DROP TABLE config_arca;
DELETE FROM flyway_schema_history WHERE version = '32';
