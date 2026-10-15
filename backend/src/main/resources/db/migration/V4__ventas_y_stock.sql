-- CU-07 (corte 1): ventas de mostrador y movimientos de stock.
-- Un comprobante de tipo VENTA y estado SIN_FACTURAR es la venta; la fase 5 suma CAE y notas de credito.
-- tipo, estado y medio son VARCHAR sin CHECK a proposito: las fases siguientes agregan valores.
-- cliente_id queda nulo (Consumidor Final) y sin FK: la tabla cliente es del corte 3. Tampoco hay caja_id (CU-10).
-- Toda tabla lleva ferreteria_id y sus indices propios empiezan por esa columna.

CREATE TABLE comprobante (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id       BIGINT         NOT NULL,
    tipo                VARCHAR(20)    NOT NULL,
    estado              VARCHAR(20)    NOT NULL,
    numero              BIGINT         NOT NULL,
    clave_idempotencia  VARCHAR(36)    NOT NULL,
    cliente_id          BIGINT         NULL,
    usuario_id          BIGINT         NOT NULL,
    fecha               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    neto                DECIMAL(15, 2) NOT NULL,
    iva                 DECIMAL(15, 2) NOT NULL,
    total               DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comprobante_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_comprobante_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_comprobante_numero UNIQUE (ferreteria_id, numero),
    CONSTRAINT uk_comprobante_clave  UNIQUE (ferreteria_id, clave_idempotencia)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_comprobante_fecha ON comprobante (ferreteria_id, fecha);

-- Copia los datos del articulo al momento de la venta: los cambios posteriores no alteran el renglon.
CREATE TABLE item_comprobante (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT         NOT NULL,
    comprobante_id   BIGINT         NOT NULL,
    renglon          INT            NOT NULL,
    articulo_id      BIGINT         NOT NULL,
    descripcion      VARCHAR(200)   NOT NULL,
    unidad           VARCHAR(10)    NOT NULL,
    cantidad         DECIMAL(15, 3) NOT NULL,
    precio_unitario  DECIMAL(15, 2) NOT NULL,
    alicuota_iva     VARCHAR(10)    NOT NULL,
    total            DECIMAL(15, 2) NOT NULL,
    neto             DECIMAL(15, 2) NOT NULL,
    iva              DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_comprobante_ferreteria  FOREIGN KEY (ferreteria_id)  REFERENCES ferreteria (id),
    CONSTRAINT fk_item_comprobante_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    CONSTRAINT fk_item_comprobante_articulo    FOREIGN KEY (articulo_id)    REFERENCES articulo (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_item_comprobante ON item_comprobante (ferreteria_id, comprobante_id, renglon);

-- 1 a N desde ya (pago mixto, corte 5); el corte 1 crea siempre uno por el total.
CREATE TABLE pago (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id   BIGINT         NOT NULL,
    comprobante_id  BIGINT         NOT NULL,
    medio           VARCHAR(20)    NOT NULL,
    monto           DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pago_ferreteria  FOREIGN KEY (ferreteria_id)  REFERENCES ferreteria (id),
    CONSTRAINT fk_pago_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_pago_comprobante ON pago (ferreteria_id, comprobante_id);

-- Toda variacion de stock es un movimiento; articulo.stock_actual es su suma cacheada.
-- La cantidad lleva signo: una venta resta.
CREATE TABLE movimiento_stock (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id   BIGINT         NOT NULL,
    articulo_id     BIGINT         NOT NULL,
    tipo            VARCHAR(20)    NOT NULL,
    cantidad        DECIMAL(15, 3) NOT NULL,
    comprobante_id  BIGINT         NULL,
    usuario_id      BIGINT         NOT NULL,
    motivo          VARCHAR(200)   NULL,
    fecha           DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_movimiento_ferreteria  FOREIGN KEY (ferreteria_id)  REFERENCES ferreteria (id),
    CONSTRAINT fk_movimiento_articulo    FOREIGN KEY (articulo_id)    REFERENCES articulo (id),
    CONSTRAINT fk_movimiento_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    CONSTRAINT fk_movimiento_usuario     FOREIGN KEY (usuario_id)     REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_movimiento_articulo ON movimiento_stock (ferreteria_id, articulo_id, fecha);

-- Contador atomico de la numeracion interna: una serie por ferreteria, independiente de la de ARCA.
CREATE TABLE numerador_comprobante (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT NOT NULL,
    ultimo_numero  BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_numerador_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_numerador_ferreteria UNIQUE (ferreteria_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
