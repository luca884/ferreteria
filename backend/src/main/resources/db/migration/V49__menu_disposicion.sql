-- CU-17: una base de menú por rol y un ajuste por usuario, ambos de la misma ferretería.
-- Deploy: tabla nueva; el código anterior la ignora. Las ferreterías existentes arrancan con el menú original.
-- Rollback: volver al código anterior deja la tabla intacta y conserva las disposiciones guardadas.
CREATE TABLE menu_disposicion (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT       NOT NULL,
    alcance        VARCHAR(10)  COLLATE utf8mb4_0900_bin NOT NULL,
    rol            VARCHAR(20)  COLLATE utf8mb4_0900_bin NULL,
    usuario_id     BIGINT       NULL,
    contenido      JSON         NOT NULL,
    creada_en      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    actualizada_en DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_menu_disposicion_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_menu_disposicion_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id),
    CONSTRAINT uk_menu_disposicion_rol UNIQUE (ferreteria_id, rol),
    CONSTRAINT uk_menu_disposicion_usuario UNIQUE (ferreteria_id, usuario_id),
    CONSTRAINT ck_menu_disposicion_alcance CHECK (
        (alcance = 'ROL' AND rol IS NOT NULL AND rol IN ('DUENO', 'VENDEDOR') AND usuario_id IS NULL)
        OR (alcance = 'USUARIO' AND rol IS NULL AND usuario_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
