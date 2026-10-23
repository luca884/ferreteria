-- GDS-32 (G88): tope de venta a Consumidor Final sin identificar, por ferreteria, como el «Límite de Venta a
-- Cons.Final sin datos» de GDS. Desde ese importe (igual o superior) la venta facturable necesita un cliente con
-- documento. Arranca en el vigente de ARCA, $ 10.000.000 (RG 5700/2025, que mantiene la RG 5866/2026), y lo cambia el
-- Dueño. Aditiva: las ferreterias existentes toman el valor por defecto. En MySQL 8.4 la columna entra con
-- ALGORITHM=INSTANT; el CHECK se valida contra las filas existentes, que quedan todas en 10.000.000.
ALTER TABLE ferreteria
    ADD COLUMN tope_consumidor_final DECIMAL(15, 2) NOT NULL DEFAULT 10000000.00,
    ADD CONSTRAINT ck_ferreteria_tope_consumidor_final CHECK (tope_consumidor_final > 0);

-- Rollback: backend/src/test/resources/ferreteria/rollback-v27-tope-consumidor-final.sql
