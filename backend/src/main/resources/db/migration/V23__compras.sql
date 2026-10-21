-- CU-13, tramo 1 de compras: la factura (o el remito) del proveedor. Suma el stock de cada articulo con un movimiento
-- COMPRA y pisa su costo con el del renglon (ultimo costo, como GDS), recalculando el precio. El tramo 1 no lleva pago,
-- cuenta corriente, IVA ni total de la factura: neto = suma de cantidad x costo unitario, en la moneda de la compra
-- (todos sus articulos son de la misma moneda). Una compra no se repite: proveedor, tipo, punto de venta y numero son
-- unicos en la ferreteria. La confirmacion es idempotente por clave, como el remito. Toda tabla lleva ferreteria_id y sus
-- indices propios empiezan por esa columna.

CREATE TABLE compra (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT         NOT NULL,
    proveedor_id       BIGINT         NOT NULL,
    tipo               VARCHAR(3)     NOT NULL,
    punto_venta        INT            NOT NULL,
    numero             BIGINT         NOT NULL,
    fecha              DATE           NOT NULL,
    moneda             VARCHAR(3)     NOT NULL,
    neto               DECIMAL(15, 2) NOT NULL,
    clave_idempotencia VARCHAR(36)    NOT NULL,
    usuario_id         BIGINT         NOT NULL,
    registrada         DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_compra_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_compra_proveedor  FOREIGN KEY (proveedor_id)  REFERENCES proveedor (id),
    CONSTRAINT fk_compra_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_compra_comprobante UNIQUE (ferreteria_id, proveedor_id, tipo, punto_venta, numero),
    CONSTRAINT uk_compra_clave       UNIQUE (ferreteria_id, clave_idempotencia),
    CONSTRAINT ck_compra_tipo        CHECK (tipo IN ('FA', 'FB', 'FC', 'REM')),
    CONSTRAINT ck_compra_punto_venta CHECK (punto_venta BETWEEN 1 AND 99999),
    CONSTRAINT ck_compra_numero      CHECK (numero BETWEEN 1 AND 99999999)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_compra_fecha ON compra (ferreteria_id, fecha);

-- Copia los datos del articulo al confirmar: descripcion, unidad, y el costo y el precio que tenia antes de la compra.
CREATE TABLE item_compra (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT         NOT NULL,
    compra_id        BIGINT         NOT NULL,
    renglon          INT            NOT NULL,
    articulo_id      BIGINT         NOT NULL,
    codigo_interno   VARCHAR(30)    NOT NULL,
    descripcion      VARCHAR(200)   NOT NULL,
    unidad           VARCHAR(10)    NOT NULL,
    cantidad         DECIMAL(15, 3) NOT NULL,
    costo_unitario   DECIMAL(15, 2) NOT NULL,
    subtotal         DECIMAL(15, 2) NOT NULL,
    costo_anterior   DECIMAL(15, 2) NOT NULL,
    precio_anterior  DECIMAL(15, 2) NOT NULL,
    precio_nuevo     DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_compra_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_item_compra_compra     FOREIGN KEY (compra_id)     REFERENCES compra (id),
    CONSTRAINT fk_item_compra_articulo   FOREIGN KEY (articulo_id)   REFERENCES articulo (id),
    CONSTRAINT uk_item_compra_articulo UNIQUE (ferreteria_id, compra_id, articulo_id),
    CONSTRAINT ck_item_compra_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_item_compra_costo    CHECK (costo_unitario > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_item_compra ON item_compra (ferreteria_id, compra_id, renglon);

-- El movimiento de stock que origino una compra.
ALTER TABLE movimiento_stock
    ADD COLUMN compra_id BIGINT NULL,
    ADD CONSTRAINT fk_movimiento_compra FOREIGN KEY (compra_id) REFERENCES compra (id);
