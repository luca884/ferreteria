-- CU-07, rama 2 de facturacion (S6 del plan, 2026-10-28): el estado propio del simulador de WSFEv1 4.8, que vive en
-- facturacion.simulador y solo atiende el ambiente SIMULADOR. Es lo que ARCA guardaria del lado suyo: por serie, el ultimo
-- autorizado, y cada comprobante autorizado con todos sus campos (lo que devuelve FECompConsultar). Sobrevive a un reinicio.
-- Aditiva: las dos tablas son nuevas y ninguna tabla existente cambia.
--
-- simulador_wsfe_serie: una fila por serie de ARCA (CUIT emisor, punto de venta, CbteTipo) dentro de la ferreteria. ARCA
-- numera por CUIT; la ferreteria va adelante porque todo dato de negocio lleva ferreteria_id. ultimo_nro es lo que devuelve
-- FECompUltimoAutorizado (0: la serie no tiene ninguno) y ultima_cbte_fch la CbteFch de ese ultimo, para la regla de 10016
-- (la CbteFch no puede ser anterior a la del ultimo autorizado). La fila se toma con lock al autorizar: serializa los pedidos
-- de una misma serie. Un pedido rechazado puede dejar la fila en 0 (se crea antes de validar), sin efecto: 0 es lo mismo que
-- no tenerla.
--
-- simulador_wsfe_comprobante: cada comprobante autorizado. Los rechazados no se guardan (ARCA no los conoce). Los importes van
-- en DECIMAL(15, 2), el Double (13+2) de WSFEv1, y MonCotiz en DECIMAL(10, 6), el Double (4+6). Los arrays (Iva/AlicIva,
-- CbtesAsoc y las observaciones con que se aprobo) van como texto JSON valido: TEXT y no JSON porque MySQL guarda los numeros
-- de un JSON como DOUBLE y devolveria 100.0 en vez de 100.00. CAE de 14 digitos (String (14) del manual).
--
-- Toda tabla lleva ferreteria_id NOT NULL, y la FK entre las dos es compuesta, (ferreteria_id, serie_id) contra
-- (ferreteria_id, id), como en V29. Horas en DATETIME(6) UTC; CbteFch y vencimiento del CAE en DATE.

CREATE TABLE simulador_wsfe_serie (
    id               BIGINT  NOT NULL AUTO_INCREMENT,
    ferreteria_id    BIGINT  NOT NULL,
    cuit             BIGINT  NOT NULL,
    punto_venta      INT     NOT NULL,
    cbte_tipo        INT     NOT NULL,
    ultimo_nro       BIGINT  NOT NULL DEFAULT 0,
    ultima_cbte_fch  DATE    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_simulador_wsfe_serie_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_simulador_wsfe_serie_clave UNIQUE (ferreteria_id, cuit, punto_venta, cbte_tipo),
    CONSTRAINT uk_simulador_wsfe_serie_ferreteria_y_id UNIQUE (ferreteria_id, id),
    CONSTRAINT ck_simulador_wsfe_serie_clave CHECK (cuit BETWEEN 1 AND 99999999999 AND punto_venta BETWEEN 1 AND 99999
        AND cbte_tipo BETWEEN 1 AND 999),
    CONSTRAINT ck_simulador_wsfe_serie_ultimo CHECK (ultimo_nro BETWEEN 0 AND 99999999
        AND (ultima_cbte_fch IS NULL) = (ultimo_nro = 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE simulador_wsfe_comprobante (
    id                         BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id              BIGINT         NOT NULL,
    serie_id                   BIGINT         NOT NULL,
    cbte_nro                   BIGINT         NOT NULL,
    concepto                   INT            NOT NULL,
    doc_tipo                   INT            NOT NULL,
    doc_nro                    BIGINT         NOT NULL,
    cbte_fch                   DATE           NOT NULL,
    imp_total                  DECIMAL(15, 2) NOT NULL,
    imp_tot_conc               DECIMAL(15, 2) NOT NULL,
    imp_neto                   DECIMAL(15, 2) NOT NULL,
    imp_op_ex                  DECIMAL(15, 2) NOT NULL,
    imp_trib                   DECIMAL(15, 2) NOT NULL,
    imp_iva                    DECIMAL(15, 2) NOT NULL,
    mon_id                     VARCHAR(3)     NOT NULL,
    mon_cotiz                  DECIMAL(10, 6) NOT NULL,
    condicion_iva_receptor_id  INT            NOT NULL,
    iva                        TEXT           NOT NULL,
    cbtes_asoc                 TEXT           NOT NULL,
    periodo_asoc_desde         DATE           NULL,
    periodo_asoc_hasta         DATE           NULL,
    cae                        VARCHAR(14)    NOT NULL,
    cae_fch_vto                DATE           NOT NULL,
    observaciones              TEXT           NOT NULL,
    fch_proceso                DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_simulador_wsfe_comprobante_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_simulador_wsfe_comprobante_serie FOREIGN KEY (ferreteria_id, serie_id)
        REFERENCES simulador_wsfe_serie (ferreteria_id, id),
    CONSTRAINT uk_simulador_wsfe_comprobante_nro UNIQUE (ferreteria_id, serie_id, cbte_nro),
    CONSTRAINT ck_simulador_wsfe_comprobante_campos CHECK (cbte_nro BETWEEN 1 AND 99999999 AND concepto BETWEEN 1 AND 99
        AND doc_tipo BETWEEN 0 AND 99 AND doc_nro BETWEEN 0 AND 99999999999 AND condicion_iva_receptor_id BETWEEN 1 AND 99
        AND CHAR_LENGTH(mon_id) = 3),
    CONSTRAINT ck_simulador_wsfe_comprobante_cae CHECK (cae REGEXP '^[0-9]{14}$' AND cae_fch_vto >= cbte_fch),
    CONSTRAINT ck_simulador_wsfe_comprobante_json CHECK (IF(JSON_VALID(iva), JSON_TYPE(iva) = 'ARRAY', FALSE)
        AND IF(JSON_VALID(cbtes_asoc), JSON_TYPE(cbtes_asoc) = 'ARRAY', FALSE)
        AND IF(JSON_VALID(observaciones), JSON_TYPE(observaciones) = 'ARRAY', FALSE)),
    CONSTRAINT ck_simulador_wsfe_comprobante_periodo CHECK ((periodo_asoc_desde IS NULL) = (periodo_asoc_hasta IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Rollback: backend/src/test/resources/facturacion/rollback-v33-simulador.sql, SOLO sin datos (se niega con una sola fila
-- en cualquiera de las dos tablas; con datos se conserva todo).
