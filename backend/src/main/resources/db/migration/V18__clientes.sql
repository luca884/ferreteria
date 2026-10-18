-- Clientes (CL-001, CL-002): la tabla de clientes por ferreteria. Consumidor Final no es una fila: es "sin cliente".
-- El documento (DNI, CUIT o CUIL) es opcional pero completo: tipo y numero van juntos. El numero, guardado solo con
-- digitos, es unico por ferreteria entre los cargados; varios clientes sin documento conviven (los NULL no chocan en el
-- indice unico). No se borran, se desactivan: las ventas, presupuestos y remitos van a referenciarlos.

CREATE TABLE cliente (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT       NOT NULL,
    razon_social     VARCHAR(120) NOT NULL,
    tipo_documento   VARCHAR(10)  NULL,
    numero_documento VARCHAR(11)  NULL,
    condicion_iva    VARCHAR(30)  NOT NULL DEFAULT 'CONSUMIDOR_FINAL',
    domicilio        VARCHAR(200) NULL,
    telefono         VARCHAR(30)  NULL,
    whatsapp         VARCHAR(30)  NULL,
    email            VARCHAR(120) NULL,
    activo           BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_cliente_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_cliente_documento UNIQUE (ferreteria_id, numero_documento),
    CONSTRAINT ck_cliente_documento_completo CHECK ((tipo_documento IS NULL) = (numero_documento IS NULL)),
    INDEX ix_cliente_razon_social (ferreteria_id, razon_social)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
