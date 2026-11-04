-- ofertas (S1): la oferta de un articulo, aparte de su precio normal. Tipo PRECIO_FIJO, DESCUENTO_PORCENTAJE o
-- DESCUENTO_MONTO; valor mayor a 0; fecha de fin opcional (inclusive). Sin oferta, las tres columnas son NULL. Los
-- articulos existentes quedan sin oferta.
--
-- Deploy: el codigo anterior no conoce las columnas y las ignora (son NULL); se puede deployar en cualquier momento.
-- Rollback: ALTER TABLE articulo DROP CHECK ck_articulo_oferta, DROP COLUMN oferta_tipo, DROP COLUMN oferta_valor,
--           DROP COLUMN oferta_hasta;
ALTER TABLE articulo
    ADD COLUMN oferta_tipo VARCHAR(20) NULL,
    ADD COLUMN oferta_valor DECIMAL(15, 2) NULL,
    ADD COLUMN oferta_hasta DATE NULL,
    ADD CONSTRAINT ck_articulo_oferta CHECK (
        (oferta_tipo IS NULL AND oferta_valor IS NULL AND oferta_hasta IS NULL)
        OR (oferta_tipo IS NOT NULL AND oferta_tipo IN ('PRECIO_FIJO', 'DESCUENTO_PORCENTAJE', 'DESCUENTO_MONTO')
            AND oferta_valor IS NOT NULL AND oferta_valor > 0
            AND (oferta_tipo <> 'DESCUENTO_PORCENTAJE' OR oferta_valor < 100))
    );
