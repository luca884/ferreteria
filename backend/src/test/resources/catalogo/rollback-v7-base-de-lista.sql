-- Rollback del corte 3 (listas-y-moneda): saca la V7.
-- En prod, revertir el codigo alcanza: V7 queda como "future" (la ultima migracion aplicada) y Flyway la ignora, las
-- columnas quedan en NULL y el codigo viejo no las mapea (ddl-auto=validate no se queja de columnas de mas).
-- En dev/test, con los datos demo (V1000+, out-of-order), una V7 aplicada sin archivo local es "missing" y la
-- validacion falla al levantar el codigo anterior: hay que correr este script ANTES de levantarlo.
-- Los ajustes (V6), el costo y el precio ya guardados no se tocan.
ALTER TABLE articulo
    DROP CHECK ck_articulo_bonificacion1,
    DROP CHECK ck_articulo_bonificacion2,
    DROP CHECK ck_articulo_bonificacion3,
    DROP COLUMN bonificacion1,
    DROP COLUMN bonificacion2,
    DROP COLUMN bonificacion3,
    DROP COLUMN lista_incluye_iva;
DELETE FROM flyway_schema_history WHERE version = '7';
