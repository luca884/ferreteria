-- Esquema inicial: tenant (ferreteria), usuarios y rubros.
-- Toda tabla de negocio lleva ferreteria_id y sus indices empiezan por esa columna.

CREATE TABLE ferreteria (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    razon_social   VARCHAR(150) NOT NULL,
    cuit           VARCHAR(13)  NOT NULL,
    condicion_iva  VARCHAR(30)  NOT NULL,
    estado         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVA',
    creado_en      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_ferreteria_cuit UNIQUE (cuit)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE usuario (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT       NULL,
    email          VARCHAR(150) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    nombre         VARCHAR(100) NOT NULL,
    rol            VARCHAR(20)  NOT NULL,
    activo         BOOLEAN      NOT NULL DEFAULT TRUE,
    creado_en      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_usuario_email UNIQUE (email),
    CONSTRAINT fk_usuario_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    -- Solo el SUPER_ADMIN puede no tener ferreteria.
    CONSTRAINT ck_usuario_ferreteria CHECK (rol = 'SUPER_ADMIN' OR ferreteria_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_usuario_ferreteria ON usuario (ferreteria_id);

CREATE TABLE rubro (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT        NOT NULL,
    nombre             VARCHAR(80)   NOT NULL,
    margen_porcentaje  DECIMAL(6, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rubro_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    -- La collation _ai_ci hace que "Buloneria" y "bulonería" choquen.
    CONSTRAINT uk_rubro_nombre UNIQUE (ferreteria_id, nombre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
