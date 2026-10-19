-- Cobranza de cuenta corriente, tramo 3a (GDS-20, CC-001, CC-002, CC-026, CC-036, CC-052): el libro admite COBRO con recibo y
-- una referencia copiada, y nacen el recibo, su numerador y la imputacion de cada recibo a los comprobantes que cancela.
--
-- Modelo (M1): el pendiente de un comprobante NO se guarda, se deriva = Σ debe de sus VENTA - Σ imputaciones. El libro lleva
-- UN COBRO por recibo (haber = total del recibo), aunque el recibo impute a varios comprobantes; lo que sobra de imputar queda
-- «a cuenta» (saldo a favor) y tampoco se guarda: es total - Σ imputaciones. cliente.saldo sigue siendo la cache del libro.
--
-- recibo es INMUTABLE en 3a (sin estado ni columnas de anulacion; 3b hara un ALTER aditivo). fecha no tiene DEFAULT: la fija el
-- Clock del servicio. medio solo admite EFECTIVO y TRANSFERENCIA: protege la caja (un medio inesperado, p. ej.
-- CUENTA_CORRIENTE, corromperia el efectivo esperado); ampliarlo es un DROP/ADD como el de abajo. clave_idempotencia es la del
-- cliente HTTP: UNIQUE por ferreteria. numerador_recibo es el cuarto numerador copiado (molde V4): el UPSERT del numero toma
-- el lock de fila y serializa las cobranzas de la ferreteria.
--
-- imputacion es append-only: ningun UPDATE ni DELETE. cliente_id va propio (los pendientes y los locks se consultan por cliente
-- sin join); que el comprobante sea del cliente lo garantiza el servicio y lo verifica la invariante I2d del helper de tests.
-- UNIQUE (recibo_id, comprobante_id): un recibo imputa una sola vez a cada comprobante. ix_imputacion_cliente cubre la lectura
-- de lo imputado por (cliente, comprobante).
--
-- Libro: recibo_id (NULL salvo COBRO y ANULACION) y referencia (texto copiado, «Venta N.º n» / «Recibo N.º n»: el extracto lo
-- muestra sin que clientes lea ventas). UNIQUE (recibo_id, tipo): un COBRO y a lo sumo UNA ANULACION por recibo; respaldo de
-- la idempotencia (los NULL no chocan). El tipo ANULACION (contra-asiento del COBRO, debe > 0) entra YA en los CHECK aunque 3a no
-- lo escriba: el ADD CHECK valida toda la tabla y conviene ampliarlos una sola vez; 3b no vuelve a tocar los CHECK del libro.
--
-- DROP + ADD de los CHECK con nombres NUEVOS (_v21): MySQL no garantiza que un DROP y un ADD del mismo nombre convivan en una
-- sentencia (igual que con las FK). Van en UNA sola ALTER TABLE junto con las columnas, la FK y el UNIQUE: es atomica y el
-- libro nunca queda sin CHECKs. Supuesto validado en MigracionV21Test (RED primero) contra mysql:8.4; si MySQL rechazara la
-- combinacion se partiria en dos sentencias (primero los ADD, despues los DROP) y el test de contrato del texto se ajustaria.
-- ck_mcc_importes, ck_mcc_venta_ref, ck_mcc_exceso y uk_mcc_pago_tipo no se tocan.
--
-- Backfill: toda fila existente es VENTA con comprobante (ck_mcc_venta_ref), asi que referencia = «Venta N.º <numero del
-- comprobante>»; despues la columna pasa a NOT NULL.
--
-- Rollback: una V22 inversa (DROP imputacion, columnas/FK/UNIQUE/CHECK nuevos, restaurar ck_mcc_tipo/ck_mcc_sentido, DROP recibo
-- y numerador_recibo) SOLO sin cobranzas; con cobranzas se conserva todo y se oculta la accion.

CREATE TABLE numerador_recibo (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT NOT NULL,
    ultimo_numero  BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_numerador_recibo_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_numerador_recibo_ferreteria UNIQUE (ferreteria_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE recibo (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id       BIGINT         NOT NULL,
    numero              BIGINT         NOT NULL,
    clave_idempotencia  VARCHAR(36)    NOT NULL,
    cliente_id          BIGINT         NOT NULL,
    cliente_nombre      VARCHAR(120)   NOT NULL,
    caja_id             BIGINT         NOT NULL,
    usuario_id          BIGINT         NOT NULL,
    fecha               DATETIME(6)    NOT NULL,
    medio               VARCHAR(20)    NOT NULL,
    total               DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_recibo_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_recibo_cliente    FOREIGN KEY (cliente_id)    REFERENCES cliente (id),
    CONSTRAINT fk_recibo_caja       FOREIGN KEY (caja_id)       REFERENCES caja (id),
    CONSTRAINT fk_recibo_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_recibo_numero     UNIQUE (ferreteria_id, numero),
    CONSTRAINT uk_recibo_clave      UNIQUE (ferreteria_id, clave_idempotencia),
    CONSTRAINT ck_recibo_total      CHECK (total > 0),
    CONSTRAINT ck_recibo_medio      CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA')),
    INDEX ix_recibo_cliente (ferreteria_id, cliente_id, id),
    INDEX ix_recibo_caja    (ferreteria_id, caja_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE imputacion (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id   BIGINT         NOT NULL,
    cliente_id      BIGINT         NOT NULL,
    recibo_id       BIGINT         NOT NULL,
    comprobante_id  BIGINT         NOT NULL,
    importe         DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_imputacion_ferreteria  FOREIGN KEY (ferreteria_id)  REFERENCES ferreteria (id),
    CONSTRAINT fk_imputacion_cliente     FOREIGN KEY (cliente_id)     REFERENCES cliente (id),
    CONSTRAINT fk_imputacion_recibo      FOREIGN KEY (recibo_id)      REFERENCES recibo (id),
    CONSTRAINT fk_imputacion_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    CONSTRAINT uk_imputacion_recibo_comprobante UNIQUE (recibo_id, comprobante_id),
    CONSTRAINT ck_imputacion_importe     CHECK (importe > 0),
    INDEX ix_imputacion_cliente (ferreteria_id, cliente_id, comprobante_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE movimiento_cuenta_cliente
    ADD COLUMN recibo_id  BIGINT      NULL,
    ADD COLUMN referencia VARCHAR(60) NULL,
    ADD CONSTRAINT fk_mcc_recibo FOREIGN KEY (recibo_id) REFERENCES recibo (id),
    ADD CONSTRAINT uk_mcc_recibo_tipo UNIQUE (recibo_id, tipo),
    DROP CHECK ck_mcc_tipo,
    DROP CHECK ck_mcc_sentido,
    ADD CONSTRAINT ck_mcc_tipo_v21 CHECK (tipo IN ('VENTA', 'COBRO', 'AJUSTE', 'NC', 'ANULACION')),
    ADD CONSTRAINT ck_mcc_sentido_v21 CHECK ((tipo = 'VENTA' AND debe > 0) OR (tipo IN ('COBRO', 'NC') AND haber > 0)
        OR (tipo = 'ANULACION' AND debe > 0) OR tipo = 'AJUSTE'),
    ADD CONSTRAINT ck_mcc_recibo_ref CHECK (((tipo IN ('COBRO', 'ANULACION')) = (recibo_id IS NOT NULL))
        AND (tipo NOT IN ('COBRO', 'ANULACION') OR (comprobante_id IS NULL AND pago_id IS NULL)));

UPDATE movimiento_cuenta_cliente m
    JOIN comprobante c ON c.id = m.comprobante_id
SET m.referencia = CONCAT('Venta N.º ', c.numero)
WHERE m.tipo = 'VENTA';

ALTER TABLE movimiento_cuenta_cliente
    MODIFY COLUMN referencia VARCHAR(60) NOT NULL;
