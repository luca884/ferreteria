-- CU-07 (G55, RM-006): la venta hecha desde un remito guarda de cual. Es nulo en las ventas que no vienen de uno
-- (todas las anteriores). Un remito se factura una sola vez: la unica por remito respalda al lock de la venta
-- (MySQL admite muchos nulos en una unica).

ALTER TABLE comprobante
    ADD COLUMN remito_id BIGINT NULL,
    ADD CONSTRAINT fk_comprobante_remito FOREIGN KEY (remito_id) REFERENCES remito (id),
    ADD CONSTRAINT uk_comprobante_remito UNIQUE (remito_id);
