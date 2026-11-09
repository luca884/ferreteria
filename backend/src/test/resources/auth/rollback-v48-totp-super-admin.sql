-- Rollback de totp-super-admin: saca la V48. Se pierden el secreto, la activacion y el ultimo paso del codigo de la app.
-- En prod, revertir el codigo alcanza para que arranque: el codigo viejo no mapea las columnas (ddl-auto=validate no se
-- queja) y el super admin vuelve a entrar solo con la contraseña. Este script borra ademas el secreto guardado: si despues
-- se vuelve a deployar el TOTP, el super admin hace el alta de nuevo (otro QR).
-- En dev/test, con los datos demo (out-of-order), hay que correr este script ANTES de levantar el codigo anterior.
ALTER TABLE usuario
    DROP CHECK ck_usuario_totp,
    DROP COLUMN totp_secreto,
    DROP COLUMN totp_activado_en,
    DROP COLUMN totp_ultimo_paso;
DELETE FROM flyway_schema_history WHERE version = '48';
