-- Datos de demo SOLO para los perfiles dev y test. Contrasena de todos los usuarios: demo1234
-- Los CUIT son ficticios.

INSERT INTO ferreteria (id, razon_social, cuit, condicion_iva) VALUES
    (1, 'Ferreteria El Tornillo', '30-00000001-1', 'RESPONSABLE_INSCRIPTO'),
    (2, 'Ferreteria La Tuerca',   '30-00000002-2', 'MONOTRIBUTISTA');

INSERT INTO usuario (ferreteria_id, email, password_hash, nombre, rol) VALUES
    (NULL, 'admin@demo.local',    '$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i', 'Super Admin',       'SUPER_ADMIN'),
    (1,    'dueno@tornillo.demo', '$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i', 'Dueno El Tornillo', 'DUENO'),
    (1,    'vendedor@tornillo.demo', '$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i', 'Vendedor El Tornillo', 'VENDEDOR'),
    (2,    'dueno@tuerca.demo',   '$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i', 'Dueno La Tuerca',   'DUENO');

INSERT INTO rubro (ferreteria_id, nombre, margen_porcentaje) VALUES
    (1, 'Buloneria',    35.00),
    (1, 'Herramientas', 40.00),
    (2, 'Pintureria',   30.00);
