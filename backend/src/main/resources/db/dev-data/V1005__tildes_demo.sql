-- Correcciones de los datos demo SOLO para los perfiles dev y test. V1000 ya esta aplicada y no se edita: se
-- corrige aca con UPDATE.
-- Los nombres de las ferreterias y de los duenos demo con tilde y enie, como se ven en el menu lateral.
-- Los UPDATE son idempotentes (filtran por el valor viejo).

UPDATE ferreteria SET razon_social = 'Ferretería El Tornillo' WHERE id = 1 AND razon_social = 'Ferreteria El Tornillo';
UPDATE ferreteria SET razon_social = 'Ferretería La Tuerca'   WHERE id = 2 AND razon_social = 'Ferreteria La Tuerca';

UPDATE usuario SET nombre = 'Dueño El Tornillo' WHERE email = 'dueno@tornillo.demo' AND nombre = 'Dueno El Tornillo';
UPDATE usuario SET nombre = 'Dueño La Tuerca'   WHERE email = 'dueno@tuerca.demo'   AND nombre = 'Dueno La Tuerca';
