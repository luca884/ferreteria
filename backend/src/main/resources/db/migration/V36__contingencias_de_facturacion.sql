-- CU-07 alternativos y CU-16 punto 7, rama 7 de facturacion (cu-07-contingencias T2: S10, S5, S6 y S2; D10, decidido por
-- Luca el 2026-10-28; S12, el 2026-10-29): el reintento del Dueño, «Dejar sin factura», la marca de «salio con ticket
-- provisorio» y el backoff del job de reintento. Cambia dos tablas de V32 con pocas filas; ninguna fila existente cambia.
--
-- comprobante_fiscal, versionado. El reintento del Dueño no reescribe el registro rechazado, que es inmutable (la copia es
-- updatable=false y las alicuotas no se borran): lo deja como historia y da de alta otro, con la copia de ese momento. Por
-- eso el registro deja de ser unico por venta y pasa a haber UNO SOLO VIGENTE por venta:
--   reemplazado_en              la hora en que el reintento lo reemplazo; NULL en el vigente. Solo un RECHAZADO o un
--                               FUERA_DE_FECHA puede quedar reemplazado (ck_comprobante_fiscal_reemplazado), y despues no
--                               cambia mas.
--   uk_comprobante_fiscal_vigente  reemplaza a uk_comprobante_fiscal_comprobante: un indice funcional, como
--                               uk_intento_fiscal_abierto_por_serie de V32, cuya expresion vale comprobante_id en el vigente y
--                               NULL en los reemplazados (los NULL no chocan).
--   ix_comprobante_fiscal_comprobante  la FK compuesta a la venta (fk_comprobante_fiscal_comprobante) usaba el unico como
--                               indice; ahora tiene este, con el id al final (la historia de una venta, en orden).
-- SIN_FACTURA: el Dueño dejo sin factura una venta RECHAZADO o FUERA_DE_FECHA (S12: es definitivo). Va con el motivo
-- (texto libre obligatorio, hasta 200 caracteres), el usuario que lo hizo (de la misma ferreteria: FK compuesta a usuario,
-- como en V29) y la hora, los tres juntos y solo en SIN_FACTURA (ck_comprobante_fiscal_sin_factura). Sin numero, como los
-- estados de los que viene (ck_comprobante_fiscal_coherencia), y conserva lo que dijo ARCA tal cual: la R si venia de un
-- RECHAZADO, nada si venia de un FUERA_DE_FECHA.
-- ticket_provisorio: la venta salio con el ticket provisorio porque el CAE no llego dentro de su espera; cuando llega, se
-- avisa para reimprimir la factura (S6). La pone el motor; las filas existentes quedan sin marca.
-- ix_comprobante_fiscal_estado: el filtro por estado dentro de la ferreteria, sin la serie (ix_comprobante_fiscal_cola
-- empieza por la serie): las series con trabajo del job, la bandeja de pendientes y rechazadas y los avisos del mostrador
-- (las pendientes viejas, por registrado_en).
--
-- serie_fiscal, el backoff del job (S2, C5): fallas_seguidas, cuantas pasadas seguidas del job terminaron en un error
-- transitorio, y proximo_reintento, desde cuando el job la vuelve a atender. Los dos en cero/NULL o los dos puestos
-- (ck_serie_fiscal_backoff); vuelven a cero cuando la serie autoriza. Las filas existentes quedan sin backoff.
--
-- Horas en DATETIME(6) UTC. Los CHECK van con AND/OR: con IF() MySQL 8.4 da el error 3812.

ALTER TABLE comprobante_fiscal
    ADD COLUMN ticket_provisorio      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN motivo_sin_factura     VARCHAR(200) NULL,
    ADD COLUMN usuario_sin_factura_id BIGINT       NULL,
    ADD COLUMN sin_factura_en         DATETIME(6)  NULL,
    ADD COLUMN reemplazado_en         DATETIME(6)  NULL,
    ADD INDEX ix_comprobante_fiscal_usuario_sin_factura (ferreteria_id, usuario_sin_factura_id),
    ADD CONSTRAINT fk_comprobante_fiscal_usuario_sin_factura FOREIGN KEY (ferreteria_id, usuario_sin_factura_id)
        REFERENCES usuario (ferreteria_id, id),
    DROP CHECK ck_comprobante_fiscal_estado,
    ADD CONSTRAINT ck_comprobante_fiscal_estado CHECK (estado IN ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION',
        'AUTORIZADO', 'RECHAZADO', 'FUERA_DE_FECHA', 'CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA', 'SIN_FACTURA')),
    DROP CHECK ck_comprobante_fiscal_coherencia,
    ADD CONSTRAINT ck_comprobante_fiscal_coherencia CHECK (
        (numero IS NULL) = (cbte_fch IS NULL)
        AND (numero IS NULL) = (estado IN ('PENDIENTE', 'RECHAZADO', 'FUERA_DE_FECHA', 'SIN_FACTURA'))
        AND (cae IS NOT NULL) = (estado = 'AUTORIZADO')
        AND (cae IS NULL) = (cae_vencimiento IS NULL)
        AND (resultado <=> CASE estado WHEN 'AUTORIZADO' THEN 'A' WHEN 'RECHAZADO' THEN 'R' END
            OR (estado = 'SIN_FACTURA' AND resultado = 'R'))),
    ADD CONSTRAINT ck_comprobante_fiscal_sin_factura CHECK (
        (motivo_sin_factura IS NOT NULL) = (estado = 'SIN_FACTURA')
        AND (motivo_sin_factura IS NULL) = (usuario_sin_factura_id IS NULL)
        AND (motivo_sin_factura IS NULL) = (sin_factura_en IS NULL)
        AND (motivo_sin_factura IS NULL OR TRIM(motivo_sin_factura) <> '')),
    ADD CONSTRAINT ck_comprobante_fiscal_reemplazado CHECK (
        reemplazado_en IS NULL OR estado IN ('RECHAZADO', 'FUERA_DE_FECHA')),
    ADD INDEX ix_comprobante_fiscal_comprobante (ferreteria_id, comprobante_id, id),
    DROP INDEX uk_comprobante_fiscal_comprobante,
    ADD UNIQUE INDEX uk_comprobante_fiscal_vigente (ferreteria_id,
        (CASE WHEN reemplazado_en IS NULL THEN comprobante_id END)),
    ADD INDEX ix_comprobante_fiscal_estado (ferreteria_id, estado, registrado_en);

ALTER TABLE serie_fiscal
    ADD COLUMN fallas_seguidas   INT         NOT NULL DEFAULT 0,
    ADD COLUMN proximo_reintento DATETIME(6) NULL,
    ADD CONSTRAINT ck_serie_fiscal_backoff CHECK (
        fallas_seguidas >= 0 AND (fallas_seguidas = 0) = (proximo_reintento IS NULL));

-- Rollback: backend/src/test/resources/facturacion/rollback-v36-contingencias.sql, SOLO sin historia (se niega con un solo
-- registro SIN_FACTURA o reemplazado; con historia se conserva todo). La marca de provisorio y el backoff no lo frenan.
