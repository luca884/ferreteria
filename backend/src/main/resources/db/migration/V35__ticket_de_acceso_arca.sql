-- CU-01 paso 4, rama 6 de facturacion (S2, S3 y S6 del plan, 2026-10-29): el ticket de acceso de WSAA de cada ferreteria,
-- el estado del simulador en el borde y el bloqueo de la facturacion cuando ARCA rechaza el certificado. Aditiva: dos tablas
-- nuevas y tres columnas nullable en config_arca; ninguna columna, restriccion ni fila existente cambia.
--
-- ticket_acceso: el TA (ticket de acceso) que devuelve loginCms, uno por (ferreteria, servicio, ambiente). servicio: el de
-- WSAA (hoy, wsfe). Lo que trae el TA va todo junto o no va (ck_ticket_acceso_ta): huella_credencial es la huella de la
-- credencial activa que lo pidio (SHA-256 de su clave publica en hexa mayuscula, la de credencial_arca.huella), y un TA de
-- otra credencial no se usa; token_cifrado y sign_cifrado van con el formato del cifrador (version, id de la clave maestra,
-- nonce de 12 bytes y texto cifrado con su tag de 16; AAD con el proposito TICKET_DE_ACCESO, la ferreteria y la huella: ver
-- CifradorDeClaves), nunca en claro; generado y vence, el generationTime y el expirationTime del TA. La fila puede existir
-- sin TA (antes del primer loginCms, o despues de descartarlo).
-- El lease (lease_dueno y lease_vence, los dos o ninguno, como en serie_fiscal de V32) es de quien esta pidiendo el TA: vale
-- entre las JVM de un deploy, asi se pide de a uno. espera_hasta: antes de esa hora no se llama a WSAA (los faults
-- transitorios, coe.alreadyAuthenticated y los errores del sistema de C4). ultima_falla (el codigo de ARCA) y
-- ultima_falla_en: lo ultimo que fallo, para el log y la pantalla.
--
-- ticket_simulado: el estado del simulador de WSAA en el borde, uno por (ferreteria, servicio). huella: el SHA-256 del token
-- y el sign que emitio (no los guarda); con eso el simulador verifica el Auth que le llega y la regla del lapso del TA.
-- generado y vence: los del TA simulado.
--
-- config_arca: el bloqueo de C4 (un fault de CREDENCIAL: cms.cert.* o coe.notAuthorized). bloqueo_codigo, el codigo de ARCA;
-- bloqueo_mensaje, su faultstring; bloqueado_en, la hora. Codigo y hora van juntos, y el mensaje solo con codigo
-- (ck_config_arca_bloqueo). Las filas existentes quedan sin bloqueo.
--
-- Toda tabla lleva ferreteria_id NOT NULL con su FK. Horas en DATETIME(6) UTC. Las huellas se comparan con distincion de
-- mayusculas (REGEXP_LIKE con 'c'): la intercalacion de la tabla no la hace. Los CHECK van con AND/OR: con IF() MySQL 8.4 da
-- el error 3812.

CREATE TABLE ticket_acceso (
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT          NOT NULL,
    servicio           VARCHAR(32)     NOT NULL,
    ambiente           VARCHAR(20)     NOT NULL,
    huella_credencial  CHAR(64)        NULL,
    token_cifrado      VARBINARY(8192) NULL,
    sign_cifrado       VARBINARY(1024) NULL,
    generado           DATETIME(6)     NULL,
    vence              DATETIME(6)     NULL,
    lease_dueno        VARCHAR(36)     NULL,
    lease_vence        DATETIME(6)     NULL,
    espera_hasta       DATETIME(6)     NULL,
    ultima_falla       VARCHAR(64)     NULL,
    ultima_falla_en    DATETIME(6)     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ticket_acceso_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_ticket_acceso_clave UNIQUE (ferreteria_id, servicio, ambiente),
    CONSTRAINT ck_ticket_acceso_ambiente CHECK (ambiente IN ('SIMULADOR', 'HOMOLOGACION', 'PRODUCCION')),
    CONSTRAINT ck_ticket_acceso_huella CHECK (
        huella_credencial IS NULL OR REGEXP_LIKE(huella_credencial, '^[0-9A-F]{64}$', 'c')),
    CONSTRAINT ck_ticket_acceso_ta CHECK (
        (huella_credencial IS NULL AND token_cifrado IS NULL AND sign_cifrado IS NULL AND generado IS NULL
            AND vence IS NULL)
        OR (huella_credencial IS NOT NULL AND token_cifrado IS NOT NULL AND sign_cifrado IS NOT NULL
            AND generado IS NOT NULL AND vence IS NOT NULL)),
    CONSTRAINT ck_ticket_acceso_lease CHECK ((lease_dueno IS NULL) = (lease_vence IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ticket_simulado (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT      NOT NULL,
    servicio       VARCHAR(32) NOT NULL,
    huella         CHAR(64)    NOT NULL,
    generado       DATETIME(6) NOT NULL,
    vence          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ticket_simulado_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_ticket_simulado_clave UNIQUE (ferreteria_id, servicio),
    CONSTRAINT ck_ticket_simulado_huella CHECK (REGEXP_LIKE(huella, '^[0-9A-F]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE config_arca
    ADD COLUMN bloqueo_codigo  VARCHAR(64)  NULL,
    ADD COLUMN bloqueo_mensaje VARCHAR(500) NULL,
    ADD COLUMN bloqueado_en    DATETIME(6)  NULL,
    ADD CONSTRAINT ck_config_arca_bloqueo CHECK (
        (bloqueo_codigo IS NULL) = (bloqueado_en IS NULL) AND (bloqueo_mensaje IS NULL OR bloqueo_codigo IS NOT NULL));

-- Rollback: backend/src/test/resources/facturacion/rollback-v35-ticket-de-acceso-arca.sql, SOLO sin bloqueos (se niega con
-- un solo config_arca.bloqueo_codigo no nulo; con un bloqueo se conserva todo). Los tickets no frenan el rollback: el TA se
-- vuelve a pedir y el simulador emite otro. Va antes que rollback-v34-credencial.sql.
