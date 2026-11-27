ALTER TABLE usuario
    ADD COLUMN cuenta_demo BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE usuario SET cuenta_demo = TRUE WHERE email = 'demo@prueba.test';
