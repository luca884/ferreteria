-- Clientes (CL-004): la venta, el presupuesto y el remito referencian al cliente elegido en el mostrador (F9) y guardan una
-- copia de su razon social: renombrar al cliente no cambia lo ya emitido. Sin cliente (NULL) es Consumidor Final; en el
-- presupuesto y el remito el texto libre de siempre sigue valiendo cuando no se elige uno.
-- comprobante.cliente_id existia desde V4 sin FK, siempre nulo. La copia del nombre pasa de 80 a 120 en el presupuesto y el
-- remito para que quepa una razon social entera. Sin ON DELETE: un cliente con documentos no se borra, se desactiva.

ALTER TABLE comprobante
    ADD COLUMN cliente_nombre VARCHAR(120) NULL AFTER cliente_id,
    ADD CONSTRAINT fk_comprobante_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id);

ALTER TABLE presupuesto
    ADD COLUMN cliente_id BIGINT NULL AFTER usuario_id,
    MODIFY COLUMN cliente_nombre VARCHAR(120) NULL,
    ADD CONSTRAINT fk_presupuesto_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id);

ALTER TABLE remito
    ADD COLUMN cliente_id BIGINT NULL AFTER usuario_id,
    MODIFY COLUMN cliente_nombre VARCHAR(120) NULL,
    ADD CONSTRAINT fk_remito_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id);
