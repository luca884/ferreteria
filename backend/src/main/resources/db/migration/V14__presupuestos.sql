-- CU-07 (GDS-13, PP-001): presupuesto como cotizacion con estados, separado del comprobante.
-- No tiene caja, pagos, CAE ni forma de pago, y no toca el stock: por eso va en tablas propias y no como un tipo de
-- comprobante (no entra por error en la caja, los reportes ni la numeracion de las ventas). Su numero es propio.
-- estado es VARCHAR sin CHECK, como el del comprobante. "Vencido" no se guarda: se calcula con vence.
-- cliente_nombre es texto libre hasta que exista la tabla de clientes. No lleva recargo: depende de la forma de pago, que
-- se elige al cobrar. Toda tabla lleva ferreteria_id y sus indices propios empiezan por esa columna.

CREATE TABLE presupuesto (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id        BIGINT         NOT NULL,
    numero               BIGINT         NOT NULL,
    clave_idempotencia   VARCHAR(36)    NOT NULL,
    usuario_id           BIGINT         NOT NULL,
    fecha                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    vence                DATE           NOT NULL,
    estado               VARCHAR(20)    NOT NULL,
    cliente_nombre       VARCHAR(80)    NULL,
    observaciones        VARCHAR(200)   NULL,
    subtotal             DECIMAL(15, 2) NOT NULL,
    descuento_porcentaje DECIMAL(5, 2)  NOT NULL,
    neto                 DECIMAL(15, 2) NOT NULL,
    iva                  DECIMAL(15, 2) NOT NULL,
    total                DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_presupuesto_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_presupuesto_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_presupuesto_numero UNIQUE (ferreteria_id, numero),
    CONSTRAINT uk_presupuesto_clave  UNIQUE (ferreteria_id, clave_idempotencia)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_presupuesto_fecha ON presupuesto (ferreteria_id, fecha);
CREATE INDEX ix_presupuesto_estado ON presupuesto (ferreteria_id, estado, fecha);

-- Copia los datos del articulo al momento de emitirlo, como item_comprobante.
CREATE TABLE item_presupuesto (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id        BIGINT         NOT NULL,
    presupuesto_id       BIGINT         NOT NULL,
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
    CONSTRAINT fk_item_presupuesto_ferreteria   FOREIGN KEY (ferreteria_id)   REFERENCES ferreteria (id),
    CONSTRAINT fk_item_presupuesto_presupuesto  FOREIGN KEY (presupuesto_id)  REFERENCES presupuesto (id),
    CONSTRAINT fk_item_presupuesto_articulo     FOREIGN KEY (articulo_id)     REFERENCES articulo (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_item_presupuesto ON item_presupuesto (ferreteria_id, presupuesto_id, renglon);

-- Contador atomico de la numeracion del presupuesto: una serie por ferreteria, aparte de la de las ventas.
CREATE TABLE numerador_presupuesto (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT NOT NULL,
    ultimo_numero  BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_numerador_presupuesto_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_numerador_presupuesto_ferreteria UNIQUE (ferreteria_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
