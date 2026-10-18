-- CU-07 (corte 2): descuentos de la venta (GDS-12). El renglon lleva su descuento en % y el comprobante el global en %,
-- que se aplica a cada renglon (prorrateo): el total sigue siendo la suma de los renglones (GDS-37).
-- Las ventas anteriores no tenian descuentos: su subtotal y su bruto son el total, y los porcentajes quedan en 0.
-- Hasta 99,99 %: un descuento del 100 % dejaria la venta en $0.

ALTER TABLE comprobante
    ADD COLUMN subtotal             DECIMAL(15, 2) NULL,
    ADD COLUMN descuento_porcentaje DECIMAL(5, 2)  NOT NULL DEFAULT 0;

UPDATE comprobante SET subtotal = total;

ALTER TABLE comprobante
    MODIFY COLUMN subtotal DECIMAL(15, 2) NOT NULL,
    ADD CONSTRAINT ck_comprobante_descuento CHECK (descuento_porcentaje >= 0 AND descuento_porcentaje <= 99.99);

ALTER TABLE item_comprobante
    ADD COLUMN bruto                DECIMAL(15, 2) NULL,
    ADD COLUMN descuento_porcentaje DECIMAL(5, 2)  NOT NULL DEFAULT 0;

UPDATE item_comprobante SET bruto = total;

ALTER TABLE item_comprobante
    MODIFY COLUMN bruto DECIMAL(15, 2) NOT NULL,
    ADD CONSTRAINT ck_item_comprobante_descuento CHECK (descuento_porcentaje >= 0 AND descuento_porcentaje <= 99.99);
