-- CU-04: mapeo, IVA y bonificaciones de la lista de cada proveedor; precio de lista; importaciones aplicadas.
ALTER TABLE proveedor
    ADD COLUMN mapeo_lista       JSON          NULL,
    ADD COLUMN lista_incluye_iva BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN bonificacion1     DECIMAL(5, 2) NULL,
    ADD COLUMN bonificacion2     DECIMAL(5, 2) NULL,
    ADD COLUMN bonificacion3     DECIMAL(5, 2) NULL,
    ADD CONSTRAINT ck_proveedor_bonificacion1 CHECK (bonificacion1 IS NULL OR bonificacion1 BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_proveedor_bonificacion2 CHECK (bonificacion2 IS NULL OR bonificacion2 BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_proveedor_bonificacion3 CHECK (bonificacion3 IS NULL OR bonificacion3 BETWEEN 0 AND 100);

-- Precio de lista tal como vino en el archivo (con IVA si la lista lo trae, sin bonificar).
ALTER TABLE articulo
    ADD COLUMN precio_lista_proveedor DECIMAL(15, 2) NULL;

CREATE TABLE importacion_lista (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT       NOT NULL,
    proveedor_id   BIGINT       NOT NULL,
    usuario_id     BIGINT       NOT NULL,
    archivo        VARCHAR(255) NOT NULL,
    fecha          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    suben          INT          NOT NULL,
    bajan          INT          NOT NULL,
    nuevos         INT          NOT NULL,
    sin_cambio     INT          NOT NULL,
    no_estan       INT          NOT NULL,
    con_error      INT          NOT NULL,
    estado         VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_importacion_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_importacion_proveedor  FOREIGN KEY (proveedor_id)  REFERENCES proveedor (id),
    CONSTRAINT fk_importacion_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_importacion_proveedor ON importacion_lista (ferreteria_id, proveedor_id, fecha);
