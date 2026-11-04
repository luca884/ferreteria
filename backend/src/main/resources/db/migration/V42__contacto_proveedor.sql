-- mejoras-ferreteros-bloque-1 (S4): los datos para comunicarse con el proveedor, como los del cliente: la persona de
-- contacto, el telefono, el WhatsApp y el correo. Todos opcionales; los proveedores existentes quedan sin ellos.
--
-- Deploy: el codigo anterior no conoce las columnas y las ignora (son NULL); se puede deployar en cualquier momento.
-- Rollback: ALTER TABLE proveedor DROP COLUMN contacto, DROP COLUMN telefono, DROP COLUMN whatsapp, DROP COLUMN email;
ALTER TABLE proveedor
    ADD COLUMN contacto VARCHAR(100) NULL,
    ADD COLUMN telefono VARCHAR(30)  NULL,
    ADD COLUMN whatsapp VARCHAR(30)  NULL,
    ADD COLUMN email    VARCHAR(120) NULL;
