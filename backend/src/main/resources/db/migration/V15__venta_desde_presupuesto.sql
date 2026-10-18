-- CU-07 (GDS-13, PP-006): la venta hecha desde un presupuesto guarda de cual. Es nulo en las ventas que no vienen de uno
-- (todas las anteriores). Un presupuesto se factura una sola vez: la unica por presupuesto respalda al lock de la venta
-- (MySQL admite muchos nulos en una unica).

ALTER TABLE comprobante
    ADD COLUMN presupuesto_id BIGINT NULL,
    ADD CONSTRAINT fk_comprobante_presupuesto FOREIGN KEY (presupuesto_id) REFERENCES presupuesto (id),
    ADD CONSTRAINT uk_comprobante_presupuesto UNIQUE (presupuesto_id);
