-- CU-08, rama 8 de facturacion (cu-08-nota-de-credito T3: S3 y S8; contratos C1 y C2; D11, D12 y D13, decididos por Luca
-- el 2026-10-28, y los detalles el 2026-10-29): el comprobante asociado de la nota de credito fiscal. Cambia una tabla de
-- V32 con pocas filas; ninguna fila existente cambia.
--
-- comprobante_fiscal, el registro de una NOTA_CREDITO (la clase ya estaba en V32, con su CbteTipo 3, 8 o 13 en
-- ck_comprobante_fiscal_tipo) lleva la factura que anula, tal cual va a ARCA en CbtesAsoc. Se copia del registro de la
-- factura al dar de alta la NC (y otra vez en cada «Reintentar»), y despues no cambia (la copia es updatable=false):
--   asociado_id             el registro de la factura, de la misma ferreteria: FK compuesta a comprobante_fiscal
--                           (ferreteria_id, id), por uk_comprobante_fiscal_ferreteria_y_id de V32, con su indice
--                           ix_comprobante_fiscal_asociado.
--   asociado_cbte_tipo      Tipo de CbteAsoc: el CbteTipo de la factura (1, 6 u 11), el de la letra de la NC
--                           (ck_comprobante_fiscal_asociado: NC A con Factura A, B con B y C con C; ARCA da 10040 si no).
--   asociado_punto_venta    PtoVta de CbteAsoc, entre 1 y 99999.
--   asociado_numero         Nro de CbteAsoc, entre 1 y 99999999.
--   asociado_cuit           Cuit de CbteAsoc: la CUIT del emisor de la factura, los 11 digitos sin guiones.
--   asociado_cbte_fch       CbteFch de CbteAsoc: la de la factura.
-- Las seis van juntas y solo en una NOTA_CREDITO: una NC sin asociado es el 10197 de ARCA, y una factura no ajusta a
-- otro comprobante (ck_comprobante_fiscal_asociado). Una NC por factura no lleva un unico aca: lo garantizan el unico de la
-- NC por venta en comprobante (V37) y el unico vigente por comprobante de V36. La historia de una NC rechazada y
-- reintentada apunta a la misma factura.
--
-- Los CHECK van con AND/OR: con IF() MySQL 8.4 da el error 3812.

ALTER TABLE comprobante_fiscal
    ADD COLUMN asociado_id          BIGINT      NULL,
    ADD COLUMN asociado_cbte_tipo   INT         NULL,
    ADD COLUMN asociado_punto_venta INT         NULL,
    ADD COLUMN asociado_numero      BIGINT      NULL,
    ADD COLUMN asociado_cuit        VARCHAR(11) NULL,
    ADD COLUMN asociado_cbte_fch    DATE        NULL,
    ADD INDEX ix_comprobante_fiscal_asociado (ferreteria_id, asociado_id),
    ADD CONSTRAINT fk_comprobante_fiscal_asociado FOREIGN KEY (ferreteria_id, asociado_id)
        REFERENCES comprobante_fiscal (ferreteria_id, id),
    ADD CONSTRAINT ck_comprobante_fiscal_asociado CHECK (
        (asociado_id IS NULL) = (clase = 'FACTURA')
        AND (asociado_id IS NULL) = (asociado_cbte_tipo IS NULL)
        AND (asociado_id IS NULL) = (asociado_punto_venta IS NULL)
        AND (asociado_id IS NULL) = (asociado_numero IS NULL)
        AND (asociado_id IS NULL) = (asociado_cuit IS NULL)
        AND (asociado_id IS NULL) = (asociado_cbte_fch IS NULL)
        AND (asociado_id IS NULL OR (
            asociado_cbte_tipo = CASE letra WHEN 'A' THEN 1 WHEN 'B' THEN 6 WHEN 'C' THEN 11 END
            AND asociado_punto_venta BETWEEN 1 AND 99999
            AND asociado_numero BETWEEN 1 AND 99999999
            AND CHAR_LENGTH(asociado_cuit) = 11)));

-- Rollback: backend/src/test/resources/facturacion/rollback-v38-nota-de-credito-fiscal.sql, SOLO sin notas de credito (se
-- niega con un solo registro NOTA_CREDITO; con alguno se conserva todo).
