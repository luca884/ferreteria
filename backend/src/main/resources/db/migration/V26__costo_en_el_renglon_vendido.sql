-- Reportes de ventas (G66, GDS-08): cada renglon vendido copia el costo del articulo al momento de vender, para que la
-- ganancia no cambie cuando el costo cambia despues. costo_unitario es sin IVA y en pesos (un articulo en USD se pasa
-- con el dolar vigente); costo es costo_unitario x cantidad, a 2 decimales.
-- Aditiva: las ventas existentes quedan sin costo (NULL) y los reportes las avisan aparte; no se completan con el
-- costo de hoy porque daria una ganancia falsa. En MySQL 8.4 las columnas entran con ALGORITHM=INSTANT; el CHECK se
-- valida contra las filas existentes, que son todas NULL.
ALTER TABLE item_comprobante
    ADD COLUMN costo_unitario DECIMAL(15, 4) NULL,
    ADD COLUMN costo DECIMAL(15, 2) NULL,
    ADD CONSTRAINT ck_item_comprobante_costo CHECK (
        (costo_unitario IS NULL AND costo IS NULL)
        OR (costo_unitario IS NOT NULL AND costo IS NOT NULL AND costo_unitario >= 0 AND costo >= 0));

-- Rollback: backend/src/test/resources/ventas/rollback-v26-costo-vendido.sql
