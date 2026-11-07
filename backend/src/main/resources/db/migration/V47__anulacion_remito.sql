-- Quien anulo el remito y cuando (recorrido del 06/11, #70/#81; GDS G80: las anulaciones con usuario y fecha), como el
-- recibo (V22) y la venta. Despues de emitirlo, el remito cambia el estado y, al anularlo, estas dos columnas.
-- Migracion aditiva: no cambia ninguna fila. Los remitos ya anulados quedan sin quien ni cuando (NULL): no hay de donde
-- sacarlo. ck_remito_anulacion: las dos columnas van juntas y solo en un ANULADO; un ANULADO puede no tenerlas (los de
-- antes). La FK es por ferreteria, como todas desde V29: el usuario que anula es de la misma ferreteria que el remito.
--
-- Rollback: una V48 inversa (DROP del CHECK, la FK, el indice y las columnas); se pierde solo quien y cuando.

ALTER TABLE remito
    ADD COLUMN fecha_anulacion      DATETIME(6) NULL,
    ADD COLUMN usuario_anulacion_id BIGINT      NULL,
    ADD INDEX ix_remito_ferreteria_usuario_anulacion (ferreteria_id, usuario_anulacion_id),
    ADD CONSTRAINT fk_remito_usuario_anulacion FOREIGN KEY (ferreteria_id, usuario_anulacion_id)
        REFERENCES usuario (ferreteria_id, id),
    ADD CONSTRAINT ck_remito_anulacion CHECK (
        (fecha_anulacion IS NULL AND usuario_anulacion_id IS NULL)
        OR (fecha_anulacion IS NOT NULL AND usuario_anulacion_id IS NOT NULL AND estado = 'ANULADO'));
