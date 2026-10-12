-- CU-03: configuracion de precios, proveedores, articulos e historial de precios.
ALTER TABLE ferreteria
    ADD COLUMN margen_defecto_porcentaje DECIMAL(6, 2)  NOT NULL DEFAULT 30.00,
    ADD COLUMN redondeo_multiplo         DECIMAL(15, 2) NOT NULL DEFAULT 10.00;

CREATE TABLE proveedor (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT       NOT NULL,
    nombre         VARCHAR(100) NOT NULL,
    cuit           VARCHAR(13)  NULL,
    activo         BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_proveedor_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_proveedor_nombre UNIQUE (ferreteria_id, nombre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE articulo (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT         NOT NULL,
    codigo_interno     VARCHAR(30)    NOT NULL,
    codigo_barras      VARCHAR(30)    NULL,
    codigo_proveedor   VARCHAR(40)    NULL,
    descripcion        VARCHAR(200)   NOT NULL,
    rubro_id           BIGINT         NULL,
    proveedor_id       BIGINT         NULL,
    costo              DECIMAL(15, 2) NOT NULL,
    margen_porcentaje  DECIMAL(6, 2)  NULL,
    alicuota_iva       VARCHAR(10)    NOT NULL DEFAULT 'IVA_21',
    precio_final       DECIMAL(15, 2) NOT NULL,
    unidad             VARCHAR(10)    NOT NULL DEFAULT 'UNIDAD',
    stock_actual       DECIMAL(15, 3) NOT NULL DEFAULT 0,
    stock_minimo       DECIMAL(15, 3) NOT NULL DEFAULT 0,
    ubicacion          VARCHAR(60)    NULL,
    activo             BOOLEAN        NOT NULL DEFAULT TRUE,
    creado_en          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_articulo_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_articulo_rubro      FOREIGN KEY (rubro_id)      REFERENCES rubro (id),
    CONSTRAINT fk_articulo_proveedor  FOREIGN KEY (proveedor_id)  REFERENCES proveedor (id),
    CONSTRAINT uk_articulo_codigo_interno   UNIQUE (ferreteria_id, codigo_interno),
    -- NULLs no chocan: un articulo sin proveedor o sin codigo de proveedor no participa.
    CONSTRAINT uk_articulo_codigo_proveedor UNIQUE (ferreteria_id, proveedor_id, codigo_proveedor)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Unicidad entre activos: la controla el servicio (CU-03). El indice sirve al lector de CU-06.
CREATE INDEX ix_articulo_codigo_barras ON articulo (ferreteria_id, codigo_barras);
CREATE INDEX ix_articulo_rubro         ON articulo (ferreteria_id, rubro_id);

CREATE TABLE historial_precio (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT         NOT NULL,
    articulo_id      BIGINT         NOT NULL,
    costo_anterior   DECIMAL(15, 2) NULL,
    costo_nuevo      DECIMAL(15, 2) NOT NULL,
    precio_anterior  DECIMAL(15, 2) NULL,
    precio_nuevo     DECIMAL(15, 2) NOT NULL,
    origen           VARCHAR(20)    NOT NULL,
    fecha            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    usuario_id       BIGINT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_historial_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_historial_articulo   FOREIGN KEY (articulo_id)   REFERENCES articulo (id),
    CONSTRAINT fk_historial_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_historial_articulo ON historial_precio (ferreteria_id, articulo_id, fecha);
