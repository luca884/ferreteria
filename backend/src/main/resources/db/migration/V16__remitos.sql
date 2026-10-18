-- CU-07 (G55, RM-001): remito como documento de entrega, separado del comprobante y del presupuesto.
-- No tiene caja, pagos, CAE ni forma de pago, pero SI descuenta el stock al emitirlo (RM-003): cada salida es un
-- movimiento REMITO con remito_id. Su numero es propio. estado es VARCHAR sin CHECK, como el del comprobante.
-- cliente_nombre es texto libre hasta que exista la tabla de clientes. No lleva recargo: depende de la forma de pago, que
-- se elige al facturar. Toda tabla lleva ferreteria_id y sus indices propios empiezan por esa columna.

CREATE TABLE remito (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id        BIGINT         NOT NULL,
    numero               BIGINT         NOT NULL,
    clave_idempotencia   VARCHAR(36)    NOT NULL,
    usuario_id           BIGINT         NOT NULL,
    fecha                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    estado               VARCHAR(20)    NOT NULL,
    cliente_nombre       VARCHAR(80)    NULL,
    observaciones        VARCHAR(200)   NULL,
    subtotal             DECIMAL(15, 2) NOT NULL,
    descuento_porcentaje DECIMAL(5, 2)  NOT NULL,
    neto                 DECIMAL(15, 2) NOT NULL,
    iva                  DECIMAL(15, 2) NOT NULL,
    total                DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_remito_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_remito_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_remito_numero UNIQUE (ferreteria_id, numero),
    CONSTRAINT uk_remito_clave  UNIQUE (ferreteria_id, clave_idempotencia)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_remito_fecha ON remito (ferreteria_id, fecha);
CREATE INDEX ix_remito_estado ON remito (ferreteria_id, estado, fecha);

-- Copia los datos del articulo al momento de emitirlo, como item_comprobante.
CREATE TABLE item_remito (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id        BIGINT         NOT NULL,
    remito_id            BIGINT         NOT NULL,
    renglon              INT            NOT NULL,
    articulo_id          BIGINT         NOT NULL,
    descripcion          VARCHAR(200)   NOT NULL,
    unidad               VARCHAR(10)    NOT NULL,
    cantidad             DECIMAL(15, 3) NOT NULL,
    precio_unitario      DECIMAL(15, 2) NOT NULL,
    alicuota_iva         VARCHAR(10)    NOT NULL,
    bruto                DECIMAL(15, 2) NOT NULL,
    descuento_porcentaje DECIMAL(5, 2)  NOT NULL,
    total                DECIMAL(15, 2) NOT NULL,
    neto                 DECIMAL(15, 2) NOT NULL,
    iva                  DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_remito_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_item_remito_remito     FOREIGN KEY (remito_id)     REFERENCES remito (id),
    CONSTRAINT fk_item_remito_articulo   FOREIGN KEY (articulo_id)   REFERENCES articulo (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_item_remito ON item_remito (ferreteria_id, remito_id, renglon);

-- Contador atomico de la numeracion del remito: una serie por ferreteria, aparte de la de las ventas y la de los presupuestos.
CREATE TABLE numerador_remito (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT NOT NULL,
    ultimo_numero  BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_numerador_remito_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_numerador_remito_ferreteria UNIQUE (ferreteria_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- El movimiento de stock que origino un remito: su salida, su anulacion o la diferencia que concilio la venta (RM-006).
ALTER TABLE movimiento_stock
    ADD COLUMN remito_id BIGINT NULL,
    ADD CONSTRAINT fk_movimiento_remito FOREIGN KEY (remito_id) REFERENCES remito (id);
