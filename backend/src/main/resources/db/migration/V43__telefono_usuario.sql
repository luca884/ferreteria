-- mejoras-ferreteros-bloque-1 (S5): el telefono de cada usuario de la ferreteria (Dueño o Vendedor), opcional. Los
-- usuarios existentes quedan sin el.
--
-- Deploy: el codigo anterior no conoce la columna y la ignora (es NULL); se puede deployar en cualquier momento.
-- Rollback: ALTER TABLE usuario DROP COLUMN telefono;
ALTER TABLE usuario
    ADD COLUMN telefono VARCHAR(30) NULL;
