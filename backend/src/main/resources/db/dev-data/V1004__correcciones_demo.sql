-- Correcciones de los datos demo SOLO para los perfiles dev y test. V1000 y V1001 ya estan aplicadas y no se
-- editan: se corrige aca con UPDATE.
-- 1. Los rubros demo con tilde, como los escribiria la ferreteria.
-- 2. Los CUIT de los proveedores demo con el digito verificador correcto: los proveedores validan el CUIT al
--    guardarse, y con el ficticio de V1001 no se podian editar desde la pantalla.
-- Los UPDATE son idempotentes (filtran por el valor viejo).

UPDATE rubro SET nombre = 'Bulonería'  WHERE ferreteria_id = 1 AND nombre = 'Buloneria';
UPDATE rubro SET nombre = 'Pinturería' WHERE ferreteria_id = 2 AND nombre = 'Pintureria';

UPDATE proveedor SET cuit = '30-00000011-2' WHERE id = 1 AND cuit = '30-00000011-1';
UPDATE proveedor SET cuit = '30-00000022-8' WHERE id = 3 AND cuit = '30-00000022-2';
