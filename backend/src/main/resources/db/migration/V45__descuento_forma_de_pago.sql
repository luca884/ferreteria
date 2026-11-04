-- Descuento en efectivo (relevamiento de ferreteros, bloque 3): cada forma de pago puede llevar un descuento en %, de 0 a
-- 99,99, que se aplica a la parte de la venta que se paga con ella. Una forma lleva recargo o descuento, nunca los dos.
-- Las formas que ya existen quedan con descuento 0 (DEFAULT), igual que las que se siembren despues.
-- En la venta el descuento se guarda como RECARGO NEGATIVO, con la misma formula de siempre: subtotal - descuento +
-- recargo = total, y pago.monto = la parte + su recargo. Por eso los recargos de comprobante, renglon y pago dejan de
-- exigir >= 0. El pago sigue atado a su %: de -99,99 a 200 y con el mismo signo que su recargo (o cero).
-- Migracion aditiva: no cambia ninguna fila.

ALTER TABLE forma_pago
    ADD COLUMN descuento_porcentaje DECIMAL(5, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_forma_pago_descuento CHECK (descuento_porcentaje >= 0 AND descuento_porcentaje <= 99.99),
    ADD CONSTRAINT ck_forma_pago_recargo_o_descuento CHECK (recargo_porcentaje = 0 OR descuento_porcentaje = 0);

ALTER TABLE comprobante
    DROP CHECK ck_comprobante_recargo;

ALTER TABLE item_comprobante
    DROP CHECK ck_item_comprobante_recargo;

ALTER TABLE pago
    DROP CHECK ck_pago_recargo,
    ADD CONSTRAINT ck_pago_recargo_v45 CHECK (recargo_porcentaje >= -99.99 AND recargo_porcentaje <= 200
        AND (recargo_porcentaje >= 0 OR recargo <= 0) AND (recargo_porcentaje <= 0 OR recargo >= 0));
