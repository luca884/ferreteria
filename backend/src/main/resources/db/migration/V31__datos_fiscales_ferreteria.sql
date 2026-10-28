-- CU-01 paso 1 (D1 del plan de facturacion, 2026-10-28): los datos del emisor que la factura imprime (RG 1415, Anexo II)
-- y que hoy la ferreteria no tiene. Los carga y edita el Dueño: el domicilio comercial y el inicio de actividades del local
-- donde se factura, y los Ingresos Brutos como texto (numero, Convenio Multilateral, Exento o No contribuyente).
-- Aditiva: las tres columnas son nullable y las ferreterias existentes quedan sin datos; completos son requisito para
-- habilitar la facturacion, no para guardar. El servicio guarda un texto en blanco como NULL, y los CHECK lo aseguran.
ALTER TABLE ferreteria
    ADD COLUMN domicilio_comercial VARCHAR(200) NULL,
    ADD COLUMN inicio_actividades  DATE         NULL,
    ADD COLUMN ingresos_brutos     VARCHAR(50)  NULL,
    ADD CONSTRAINT ck_ferreteria_domicilio_comercial CHECK (domicilio_comercial IS NULL OR TRIM(domicilio_comercial) <> ''),
    ADD CONSTRAINT ck_ferreteria_ingresos_brutos CHECK (ingresos_brutos IS NULL OR TRIM(ingresos_brutos) <> '');

-- Rollback: backend/src/test/resources/ferreteria/rollback-v31-datos-fiscales.sql
