-- CU-08 Anular una venta, rama 8 de facturacion (cu-08-nota-de-credito T2: S4, S9, S10 y S11; D11, D12 y D13, decididos
-- por Luca el 2026-10-28, y los detalles el 2026-10-29): la nota de credito y la anulacion interna. Cambia solo comprobante;
-- ninguna fila existente cambia.
--
-- La nota de credito (NC, con CAE propio) y la anulacion interna (sin documento fiscal) son el mismo comprobante: tipo
-- NOTA_CREDITO, con su numero interno de siempre (el numerador de la venta), quien y cuando (usuario_id y fecha), sin caja
-- ni pagos (caja_id ya admite NULL) y con su propia clave_idempotencia. Los renglones (con su costo) y los totales son una
-- copia en positivo de los de la venta: los lectores (reportes, Ventas, stock) los restan.
--   comprobante_asociado_id  la venta que anula. FK compuesta (ferreteria_id, comprobante_asociado_id) a comprobante
--                            (ferreteria_id, id), con uk_comprobante_ferreteria_y_id de V29 como destino: una venta de otra
--                            ferreteria no se puede anular ni por error.
--   uk_comprobante_asociado  UNA sola NC por venta (S4): dos anulaciones a la vez de la misma venta dan una que entra y otra
--                            que choca. Tambien es el indice de la FK y el de los lectores que buscan la nota de una venta.
--   motivo_anulacion         el motivo de la anulacion interna (S13: obligatorio, hasta 200 caracteres). NULL en la NC con
--                            CAE, que no pide motivo (asi se distinguen las dos).
-- ck_comprobante_nota_de_credito: el asociado va si y solo si el tipo es NOTA_CREDITO, y el motivo solo en una NOTA_CREDITO y
-- con texto (no en blanco). Con AND y OR: IF() dentro de un CHECK da el error 3812. La FK no tiene acciones ON DELETE ni
-- ON UPDATE (un CHECK no puede usar columnas con acciones referenciales), y una venta no se borra (GDS-15).
-- Que el asociado sea una VENTA (y no otra NC) lo controla quien anula: un CHECK no puede mirar otra fila.
-- El tipo sigue siendo texto sin CHECK (TipoComprobante): NOTA_CREDITO no necesita cambiar la columna.
ALTER TABLE comprobante
    ADD COLUMN comprobante_asociado_id BIGINT NULL AFTER remito_id,
    ADD COLUMN motivo_anulacion VARCHAR(200) NULL AFTER comprobante_asociado_id,
    ADD CONSTRAINT uk_comprobante_asociado UNIQUE (ferreteria_id, comprobante_asociado_id),
    ADD CONSTRAINT fk_comprobante_asociado FOREIGN KEY (ferreteria_id, comprobante_asociado_id)
        REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT ck_comprobante_nota_de_credito CHECK (
        (comprobante_asociado_id IS NOT NULL) = (tipo = 'NOTA_CREDITO')
        AND (motivo_anulacion IS NULL OR (tipo = 'NOTA_CREDITO' AND TRIM(motivo_anulacion) <> '')));
