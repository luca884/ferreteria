-- Anulacion de cobranza y extracto de cuenta corriente, tramo 3b-1 (GDS-20, CC-001, CC-094, CC-095, CC-061): el recibo gana
-- estado y la auditoria de su anulacion, el libro gana el motivo y la imputacion gana la vigencia.
--
-- recibo deja de ser 100 % inmutable: lo unico que cambia despues de emitido es el estado (EMITIDO -> ANULADO) y las cuatro
-- columnas de auditoria de esa transicion (fecha, usuario, caja y motivo de la anulacion); precedente: remito.estado. Importe,
-- medio, cliente, numero y fecha del recibo siguen sin cambiar nunca. estado NOT NULL DEFAULT 'EMITIDO' deja todo recibo previo
-- como EMITIDO sin tocar filas. ck_recibo_anulacion es estricta en los dos sentidos: EMITIDO no lleva ninguna columna de
-- auditoria, ANULADO las lleva todas y un motivo de al menos 5 caracteres (respaldo grueso: la regla de «5 letras» la valida
-- la aplicacion). caja_anulacion_id es la caja ABIERTA que absorbe la anulacion (puede no ser la del recibo); por eso lleva su
-- propio indice, con el que la caja suma lo anulado en ella.
--
-- movimiento_cuenta_cliente.motivo: obligatorio para ANULACION y AJUSTE (el AJUSTE lo usa 3b-2) y prohibido para el resto
-- (ck_mcc_motivo). ANULACION ya esta admitida por los CHECK de V21 (tipo, sentido y recibo_ref: debe > 0, con recibo_id, sin
-- comprobante ni pago) y UNIQUE (recibo_id, tipo) de V21 ya garantiza a lo sumo UNA anulacion por recibo: V22 no los toca.
-- ck_mcc_motivo es valido sobre los datos actuales (ninguna fila previa es ANULACION ni AJUSTE); los tests que siembran AJUSTE
-- por SQL pasan un motivo.
--
-- imputacion.vigente: V-flag. La imputacion deja de ser puramente append-only: la UNICA mutacion permitida es pasar vigente de
-- TRUE a FALSE cuando se anula el recibo (lo hace la cuenta corriente, bajo el lock del cliente). Toda lectura de lo imputado
-- filtra vigente = true; la lectura de un recibo (reimpresion) devuelve todas. Se rechazo derivar la vigencia por subconsulta
-- (el FOR UPDATE de la cobranza leeria una foto vieja) y las contra-imputaciones. Sin indice nuevo: los filtros caen sobre
-- rangos ya cubiertos (ix_imputacion_cliente, uk_imputacion_recibo_comprobante).
--
-- MySQL acepta CHECK sobre columnas con FK (precedente: ck_mcc_recibo_ref sobre recibo_id en V21), asi que ck_recibo_anulacion
-- sobre usuario_anulacion_id y caja_anulacion_id va en la misma ALTER que las FK. Supuesto validado en MigracionV22Test.
--
-- Rollback: una V23 inversa (DROP del indice, CHECK, FK y columnas nuevas) SOLO sin anulaciones; con anulaciones se conserva todo.

ALTER TABLE recibo
    ADD COLUMN estado               VARCHAR(10)  NOT NULL DEFAULT 'EMITIDO',
    ADD COLUMN fecha_anulacion      DATETIME(6)  NULL,
    ADD COLUMN usuario_anulacion_id BIGINT       NULL,
    ADD COLUMN caja_anulacion_id    BIGINT       NULL,
    ADD COLUMN motivo_anulacion     VARCHAR(200) NULL,
    ADD CONSTRAINT fk_recibo_usuario_anulacion FOREIGN KEY (usuario_anulacion_id) REFERENCES usuario (id),
    ADD CONSTRAINT fk_recibo_caja_anulacion    FOREIGN KEY (caja_anulacion_id)    REFERENCES caja (id),
    ADD CONSTRAINT ck_recibo_estado CHECK (estado IN ('EMITIDO', 'ANULADO')),
    ADD CONSTRAINT ck_recibo_anulacion CHECK (
        (estado = 'EMITIDO' AND fecha_anulacion IS NULL AND usuario_anulacion_id IS NULL
             AND caja_anulacion_id IS NULL AND motivo_anulacion IS NULL)
        OR (estado = 'ANULADO' AND fecha_anulacion IS NOT NULL AND usuario_anulacion_id IS NOT NULL
             AND caja_anulacion_id IS NOT NULL AND motivo_anulacion IS NOT NULL
             AND CHAR_LENGTH(TRIM(motivo_anulacion)) >= 5)),
    ADD INDEX ix_recibo_caja_anulacion (ferreteria_id, caja_anulacion_id);

ALTER TABLE movimiento_cuenta_cliente
    ADD COLUMN motivo VARCHAR(200) NULL,
    ADD CONSTRAINT ck_mcc_motivo CHECK (((tipo IN ('ANULACION', 'AJUSTE')) = (motivo IS NOT NULL))
        AND (motivo IS NULL OR CHAR_LENGTH(TRIM(motivo)) >= 5));

ALTER TABLE imputacion
    ADD COLUMN vigente BOOLEAN NOT NULL DEFAULT TRUE;
