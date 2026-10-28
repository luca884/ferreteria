-- Rollback de los datos fiscales de la ferreteria: saca la V31 (domicilio comercial, inicio de actividades e Ingresos Brutos).
-- SOLO sin datos cargados. La primera sentencia es una guarda: agrega un CHECK que exige las tres columnas vacias, y MySQL
-- valida las filas existentes, asi que con un solo dato cargado el script se niega (ck_rollback_sin_datos_fiscales) ANTES de
-- tocar nada. Con datos se conserva todo: son los que la factura imprime y el Dueño los tendria que volver a cargar.
-- En prod, revertir el codigo solo no rompe nada (ddl-auto=validate no se queja de columnas de mas y son nullable); correr
-- este script junto con la vuelta atras del codigo y solo si nadie cargo datos.
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE ferreteria ADD CONSTRAINT ck_rollback_sin_datos_fiscales
    CHECK (domicilio_comercial IS NULL AND inicio_actividades IS NULL AND ingresos_brutos IS NULL);

ALTER TABLE ferreteria
    DROP CHECK ck_rollback_sin_datos_fiscales,
    DROP CHECK ck_ferreteria_domicilio_comercial,
    DROP CHECK ck_ferreteria_ingresos_brutos,
    DROP COLUMN domicilio_comercial,
    DROP COLUMN inicio_actividades,
    DROP COLUMN ingresos_brutos;
DELETE FROM flyway_schema_history WHERE version = '31';
