-- totp-super-admin: el super admin entra con email, contraseña y el código de 6 números de Google Authenticator (TOTP,
-- RFC 6238). Tres columnas en usuario, todas NULL:
--   totp_secreto      el secreto de 20 bytes cifrado con AES-256-GCM y TOTP_CLAVE_MAESTRA, atado al id del usuario
--                     (CifradorTotp: versión, nonce de 12 bytes, cifrado y tag de 16; 49 bytes). NULL = sin alta.
--   totp_activado_en  cuándo lo confirmó el primer código bueno. Un secreto con esta columna en NULL es un alta pendiente:
--                     el login pide ALTA y el QR se puede volver a pedir.
--   totp_ultimo_paso  el último paso de 30 s aceptado: un código de ese paso o de uno anterior no vale (código ya usado).
-- ck_usuario_totp: solo un SUPER_ADMIN tiene código de la app, y la activación y el último paso van juntos y con el
-- secreto. El reset por base (README, Deploy) pone las tres en NULL juntas.
--
-- Migracion aditiva: no cambia ninguna fila; todos quedan sin el código y el super admin hace el alta en su próximo login.
-- Deploy: el codigo anterior no mapea las columnas (ddl-auto=validate no se queja de columnas de mas).
--
-- Rollback: backend/src/test/resources/auth/rollback-v48-totp-super-admin.sql (DROP del CHECK y de las tres columnas, y la
-- V48 fuera del historial); se pierde el alta del código de la app.

ALTER TABLE usuario
    ADD COLUMN totp_secreto     VARBINARY(64) NULL,
    ADD COLUMN totp_activado_en DATETIME(6)   NULL,
    ADD COLUMN totp_ultimo_paso BIGINT        NULL,
    ADD CONSTRAINT ck_usuario_totp CHECK (
        (totp_secreto IS NULL AND totp_activado_en IS NULL AND totp_ultimo_paso IS NULL)
        OR (rol = 'SUPER_ADMIN' AND totp_secreto IS NOT NULL
            AND (totp_activado_en IS NULL) = (totp_ultimo_paso IS NULL)));
