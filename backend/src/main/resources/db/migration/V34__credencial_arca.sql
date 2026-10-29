-- CU-01 paso 3, rama 5 de facturacion (S6 y S15 del plan, 2026-10-28): la credencial de ARCA de cada ferreteria y la CA del
-- simulador. Aditiva: las dos tablas son nuevas y ninguna tabla existente cambia.
--
-- credencial_arca: la clave RSA que genera el sistema con su pedido de certificado (CSR) y, cuando el Dueño sube el .crt de
-- ARCA (o lo firma la CA del simulador), el certificado. La clave privada nace cifrada y nunca sale del servidor (D8): va en
-- clave_privada con el formato del cifrador (version, id de la clave maestra, nonce de 12 bytes y texto cifrado con su tag
-- de 16; AAD con el proposito, la ferreteria y la huella; ver CifradorDeClaves). huella: el SHA-256 de la clave publica
-- (SubjectPublicKeyInfo en DER), en hexa en mayusculas. cuit: el del pedido y, al activarse, el del serialNumber del
-- certificado. pedido: el CSR en PEM (dato publico, para volver a bajarlo).
-- Estados: PENDIENTE (el pedido que espera el .crt, sin certificado ni nada de lo que trae) y ACTIVA (con certificado,
-- ambiente, emisor, vigencia y fecha de activacion). uk_credencial_arca_estado: a lo sumo una PENDIENTE y una ACTIVA por
-- ferreteria; un pedido nuevo reemplaza al pendiente y deja la activa, y activar un certificado borra la activa anterior
-- con su clave. ambiente: el de la CA que firmo el certificado; ck_credencial_arca_simulador: la del simulador, y solo
-- ella, apunta a la CA del simulador de su ferreteria.
--
-- ca_simulador: la CA de simulacion de cada ferreteria (una sola, se crea la primera vez que firma), con su clave privada
-- cifrada con el mismo formato y su proposito propio en el AAD. Firma el pedido con un clic, solo en SIMULADOR (S5).
--
-- Toda tabla lleva ferreteria_id NOT NULL, y la FK entre las dos es compuesta, (ferreteria_id, ca_simulador_id) contra
-- (ferreteria_id, id), como en V29. Horas en DATETIME(6) UTC. Las huellas se comparan con distincion de mayusculas
-- (REGEXP_LIKE con 'c'): la intercalacion de la tabla no la hace.

CREATE TABLE ca_simulador (
    id             BIGINT          NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT          NOT NULL,
    certificado    TEXT            NOT NULL,
    huella         CHAR(64)        NOT NULL,
    clave_privada  VARBINARY(4096) NOT NULL,
    creada_en      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ca_simulador_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_ca_simulador_ferreteria UNIQUE (ferreteria_id),
    CONSTRAINT uk_ca_simulador_ferreteria_y_id UNIQUE (ferreteria_id, id),
    CONSTRAINT ck_ca_simulador_huella CHECK (REGEXP_LIKE(huella, '^[0-9A-F]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE credencial_arca (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT          NOT NULL,
    estado           VARCHAR(20)     NOT NULL,
    cuit             BIGINT          NOT NULL,
    huella           CHAR(64)        NOT NULL,
    clave_privada    VARBINARY(4096) NOT NULL,
    pedido           TEXT            NOT NULL,
    creada_en        DATETIME(6)     NOT NULL,
    certificado      TEXT            NULL,
    ambiente         VARCHAR(20)     NULL,
    emisor           VARCHAR(100)    NULL,
    vigente_desde    DATETIME(6)     NULL,
    vigente_hasta    DATETIME(6)     NULL,
    activada_en      DATETIME(6)     NULL,
    ca_simulador_id  BIGINT          NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_credencial_arca_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_credencial_arca_ca_simulador FOREIGN KEY (ferreteria_id, ca_simulador_id)
        REFERENCES ca_simulador (ferreteria_id, id),
    CONSTRAINT uk_credencial_arca_estado UNIQUE (ferreteria_id, estado),
    CONSTRAINT ck_credencial_arca_estado CHECK (estado IN ('PENDIENTE', 'ACTIVA')),
    CONSTRAINT ck_credencial_arca_cuit CHECK (cuit BETWEEN 1 AND 99999999999),
    CONSTRAINT ck_credencial_arca_huella CHECK (REGEXP_LIKE(huella, '^[0-9A-F]{64}$', 'c')),
    CONSTRAINT ck_credencial_arca_activa CHECK (
        (estado = 'ACTIVA' AND certificado IS NOT NULL AND ambiente IS NOT NULL AND emisor IS NOT NULL
            AND vigente_desde IS NOT NULL AND vigente_hasta IS NOT NULL AND activada_en IS NOT NULL)
        OR (estado <> 'ACTIVA' AND certificado IS NULL AND ambiente IS NULL AND emisor IS NULL AND vigente_desde IS NULL
            AND vigente_hasta IS NULL AND activada_en IS NULL)),
    CONSTRAINT ck_credencial_arca_ambiente CHECK (ambiente IS NULL OR ambiente IN ('SIMULADOR', 'HOMOLOGACION', 'PRODUCCION')),
    CONSTRAINT ck_credencial_arca_vigencia CHECK (vigente_hasta IS NULL OR vigente_desde < vigente_hasta),
    CONSTRAINT ck_credencial_arca_simulador CHECK ((ambiente <=> 'SIMULADOR') = (ca_simulador_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Rollback: backend/src/test/resources/facturacion/rollback-v34-credencial.sql, SOLO sin credenciales (se niega con una sola
-- fila en cualquiera de las dos tablas; con datos se conserva todo). Va antes que rollback-v33-simulador.sql.
