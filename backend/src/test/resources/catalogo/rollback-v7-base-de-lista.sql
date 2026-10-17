-- Rollback del corte 3 (listas-y-moneda): saca la V7. Normalmente NO hace falta: al volver al codigo anterior las
-- columnas quedan en NULL y el codigo viejo no las mapea (ddl-auto=validate no se queja de columnas de mas).
-- Solo si hay que sacar la V7 del todo. Los ajustes (V6), el costo y el precio ya guardados no se tocan.
ALTER TABLE articulo
    DROP CHECK ck_articulo_bonificacion1,
    DROP CHECK ck_articulo_bonificacion2,
    DROP CHECK ck_articulo_bonificacion3,
    DROP COLUMN bonificacion1,
    DROP COLUMN bonificacion2,
    DROP COLUMN bonificacion3,
    DROP COLUMN lista_incluye_iva;
DELETE FROM flyway_schema_history WHERE version = '7';
