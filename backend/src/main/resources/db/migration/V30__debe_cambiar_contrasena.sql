-- Recorrido del Dueño (S4): la contraseña inicial la elige otra persona (el super admin para el primer Dueño, el Dueño
-- para sus usuarios) y el sistema pide cambiarla antes de usar cualquier pantalla. La marca se prende al crear un
-- usuario o al resetearle la contraseña, y se apaga cuando el titular la cambia. Aditiva: los usuarios que ya existen
-- quedan sin marca (DEFAULT FALSE), así que desplegar no obliga a nadie a nada. En MySQL 8.4 la columna entra con
-- ALGORITHM=INSTANT.
ALTER TABLE usuario
    ADD COLUMN debe_cambiar_contrasena BOOLEAN NOT NULL DEFAULT FALSE;

-- Rollback: ALTER TABLE usuario DROP COLUMN debe_cambiar_contrasena; y DELETE FROM flyway_schema_history WHERE version = '30';
