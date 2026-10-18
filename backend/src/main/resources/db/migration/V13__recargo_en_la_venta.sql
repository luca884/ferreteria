-- CU-07 (GDS-11, RC-006): el recargo de la forma de pago en la venta.
-- comprobante.recargo y item_comprobante.recargo: el recargo de la venta y la parte que le toca a cada renglon (se reparte
-- por el total ya descontado). Subtotal - descuento + recargo = total. Las ventas anteriores no tenian: 0.
-- pago: la forma con que se pago, una copia de su nombre y su % del momento (si el Dueno los cambia despues, la venta no
-- cambia), el importe del recargo y el monto, que es lo que se cobro con ese medio: la parte del total mas su recargo.
-- La caja sigue sumando pago.monto por medio. Los pagos anteriores apuntan a la forma de su medio.

-- Una ferreteria con pagos y sin ninguna forma (creada despues de la V12, que solo siembra las que ya existian) recibe
-- las cuatro de siempre antes de enlazar.
INSERT INTO forma_pago (ferreteria_id, nombre, medio, orden)
SELECT f.id, base.nombre, base.medio, base.orden
FROM ferreteria f
         CROSS JOIN (SELECT 'Efectivo' AS nombre, 'EFECTIVO' AS medio, 1 AS orden
                     UNION ALL SELECT 'Débito', 'DEBITO', 2
                     UNION ALL SELECT 'Crédito', 'CREDITO', 3
                     UNION ALL SELECT 'Transferencia', 'TRANSFERENCIA', 4) base
WHERE EXISTS (SELECT 1 FROM pago p WHERE p.ferreteria_id = f.id)
  AND NOT EXISTS (SELECT 1 FROM forma_pago fp WHERE fp.ferreteria_id = f.id);

ALTER TABLE comprobante
    ADD COLUMN recargo DECIMAL(15, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_comprobante_recargo CHECK (recargo >= 0);

ALTER TABLE item_comprobante
    ADD COLUMN recargo DECIMAL(15, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_item_comprobante_recargo CHECK (recargo >= 0);

ALTER TABLE pago
    ADD COLUMN forma_pago_id       BIGINT         NULL,
    ADD COLUMN forma_pago_nombre   VARCHAR(60)    NULL,
    ADD COLUMN recargo_porcentaje  DECIMAL(5, 2)  NOT NULL DEFAULT 0,
    ADD COLUMN recargo             DECIMAL(15, 2) NOT NULL DEFAULT 0;

UPDATE pago p
    JOIN forma_pago fp ON fp.id = (SELECT MIN(f2.id) FROM forma_pago f2
                                   WHERE f2.ferreteria_id = p.ferreteria_id AND f2.medio = p.medio)
SET p.forma_pago_id     = fp.id,
    p.forma_pago_nombre = fp.nombre;

ALTER TABLE pago
    MODIFY COLUMN forma_pago_id BIGINT NOT NULL,
    MODIFY COLUMN forma_pago_nombre VARCHAR(60) NOT NULL,
    ADD CONSTRAINT fk_pago_forma_pago FOREIGN KEY (forma_pago_id) REFERENCES forma_pago (id),
    ADD CONSTRAINT ck_pago_recargo CHECK (recargo >= 0 AND recargo_porcentaje >= 0);

CREATE INDEX ix_pago_forma_pago ON pago (ferreteria_id, forma_pago_id);
