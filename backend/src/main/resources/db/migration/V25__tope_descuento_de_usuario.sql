-- CU-07: tope de descuento por usuario (como el «Limite de Descuento» de GDS).
-- NULL = sin tope. Va de 0 a 99,99, igual que el descuento del mostrador. El Dueño no tiene tope: la columna queda
-- NULL para el rol DUENO (lo asegura el CHECK, ademas del codigo).
-- Aditiva: los usuarios existentes quedan sin tope. En MySQL 8.4 la columna entra con ALGORITHM=INSTANT; el CHECK se
-- valida contra las filas existentes, que son todas NULL.
ALTER TABLE usuario
    ADD COLUMN tope_descuento DECIMAL(5, 2) NULL,
    ADD CONSTRAINT ck_usuario_tope_descuento CHECK (
        tope_descuento IS NULL OR (rol <> 'DUENO' AND tope_descuento BETWEEN 0 AND 99.99));

-- Rollback: backend/src/test/resources/auth/rollback-v25-tope-descuento.sql
