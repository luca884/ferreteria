-- CU-10: caja diaria. UNA sola caja abierta por ferreteria (no por usuario): abierta es TRUE si esta abierta y NULL si
-- esta cerrada (nunca FALSE). UNIQUE (ferreteria_id, abierta) deja una sola fila con TRUE porque MySQL permite repetir
-- los NULL; el CHECK impide el FALSE, que se colaria como una segunda caja "cerrada" a medias.
-- Cerrada es inmutable: el cierre guarda contado, esperado y diferencia (contado - esperado) y no se vuelve a tocar.
-- Toda tabla lleva ferreteria_id y sus indices propios empiezan por esa columna.

CREATE TABLE caja (
    id                        BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id             BIGINT         NOT NULL,
    abierta                   BOOLEAN        NULL,
    fecha_apertura            DATETIME(6)    NOT NULL,
    usuario_apertura_id       BIGINT         NOT NULL,
    efectivo_inicial          DECIMAL(15, 2) NOT NULL,
    -- El contado del ultimo cierre al abrir y la diferencia (inicial - ese contado); NULL en la primera caja.
    efectivo_cierre_anterior  DECIMAL(15, 2) NULL,
    diferencia_apertura       DECIMAL(15, 2) NULL,
    fecha_cierre              DATETIME(6)    NULL,
    usuario_cierre_id         BIGINT         NULL,
    efectivo_contado          DECIMAL(15, 2) NULL,
    efectivo_esperado         DECIMAL(15, 2) NULL,
    diferencia                DECIMAL(15, 2) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_caja_ferreteria       FOREIGN KEY (ferreteria_id)       REFERENCES ferreteria (id),
    CONSTRAINT fk_caja_usuario_apertura FOREIGN KEY (usuario_apertura_id) REFERENCES usuario (id),
    CONSTRAINT fk_caja_usuario_cierre   FOREIGN KEY (usuario_cierre_id)   REFERENCES usuario (id),
    CONSTRAINT uk_caja_abierta UNIQUE (ferreteria_id, abierta),
    CONSTRAINT ck_caja_abierta CHECK (abierta IS NULL OR abierta = TRUE)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- El historial del Dueño: las cerradas, mas nuevas primero.
CREATE INDEX ix_caja_cierre ON caja (ferreteria_id, fecha_cierre);

-- Ingresos, gastos y retiros. El importe es siempre positivo: el tipo dice si suma o resta al efectivo.
CREATE TABLE movimiento_caja (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id   BIGINT         NOT NULL,
    caja_id         BIGINT         NOT NULL,
    tipo            VARCHAR(20)    NOT NULL,
    importe         DECIMAL(15, 2) NOT NULL,
    motivo          VARCHAR(120)   NOT NULL,
    usuario_id      BIGINT         NOT NULL,
    fecha           DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_movimiento_caja_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_movimiento_caja_caja       FOREIGN KEY (caja_id)       REFERENCES caja (id),
    CONSTRAINT fk_movimiento_caja_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT ck_movimiento_caja_importe CHECK (importe > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_movimiento_caja ON movimiento_caja (ferreteria_id, caja_id);

-- Cada venta queda en la caja abierta. Las ventas anteriores a CU-10 quedan con caja_id NULL.
ALTER TABLE comprobante ADD COLUMN caja_id BIGINT NULL AFTER usuario_id;
ALTER TABLE comprobante ADD CONSTRAINT fk_comprobante_caja FOREIGN KEY (caja_id) REFERENCES caja (id);
CREATE INDEX ix_comprobante_caja ON comprobante (ferreteria_id, caja_id);
